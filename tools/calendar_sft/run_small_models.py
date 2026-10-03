"""Finish the approved two-model workflow; preserve every phase and raw result."""
from __future__ import annotations
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import time
import traceback
from small_models_common import ROOT, WORK, NAMES, read, write, digest, now, freeze_inputs

PYTHON = ROOT/'build/calendar_sft_local_gpu_venv/Scripts/python.exe'
NEW_PYTHON = ROOT/'build/calendar_sft_qwen35_venv/Scripts/python.exe'
ADB = Path(r'C:\Users\007\AppData\Local\Android\Sdk\platform-tools\adb.exe')
SERIAL = '34ae334ef3fb'
REPORT = ROOT/'docs/CALENDAR_ASSISTANT_SMALL_MODELS_RESULTS.md'

def run(name, python, script, *arguments):
    folder=WORK/'jobs'
    folder.mkdir(exist_ok=True)
    record=folder/(name+'.json')
    command=[str(python),'-X','utf8','-B',str(ROOT/'tools/calendar_sft'/script),*arguments]
    if record.exists():
        old=read(record)
        if old['command']==command and old['exit_code']==0:return old
        raise RuntimeError('Inspect the previous failed phase before retrying: '+name)
    stdout,stderr=folder/(name+'.stdout.log'),folder/(name+'.stderr.log')
    if stdout.exists() or stderr.exists():raise ValueError('Unrecorded phase already exists: '+name)
    environment=os.environ.copy()
    environment.update(HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1',HF_HUB_DISABLE_TELEMETRY='1',
                       PYTHONDONTWRITEBYTECODE='1',PYTHONIOENCODING='utf-8',TOKENIZERS_PARALLELISM='false')
    started=time.monotonic()
    with stdout.open('wb') as out,stderr.open('wb') as err:
        process=subprocess.Popen(command,cwd=ROOT,env=environment,stdin=subprocess.DEVNULL,stdout=out,stderr=err,
                                 creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        write(folder/(name+'.running.json'),{'pid':process.pid,'command':command,'started_at':now()})
        code=process.wait()
    result={'command':command,'exit_code':code,'seconds':time.monotonic()-started,'completed_at':now(),
            'stdout_sha256':digest(stdout),'stderr_sha256':digest(stderr)}
    write(record,result)
    if code:raise RuntimeError('Phase failed: '+name+'; inspect '+str(stderr))
    return result

def wait_existing(script, model, result_path, done):
    import psutil
    while True:
        if result_path.exists() and done(read(result_path)):return
        running=False
        for process in psutil.process_iter(['name','cmdline']):
            try:
                args=process.info['cmdline'] or []
                if (process.info['name'] or '').lower() in ('python.exe','python') and any(Path(a).name==script for a in args) and model in args:
                    running=True
            except (psutil.AccessDenied,psutil.NoSuchProcess):pass
        if not running:raise RuntimeError('Expected existing process ended without success: '+script+' '+model)
        time.sleep(20)

def build_and_evaluate(key):
    interpreter=PYTHON if key=='qwen3_1_7b' else NEW_PYTHON
    run(key+'_build',interpreter,'build_small_model.py','--model',key)
    run(key+'_evaluate',PYTHON,'evaluate_small_models.py','--model',key)

def adb(*args, timeout=180):
    return subprocess.check_output([str(ADB),'-s',SERIAL,*args],timeout=timeout,text=True,encoding='utf-8',
        errors='replace',creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0)).strip()

def transfer(key):
    release=WORK/key/'release'
    model=next(release.glob('*-Q4_K_M.gguf'))
    expected=digest(model)
    final='/sdcard/Download/'+model.name
    partial=final+'.part'
    if adb('get-state')!='device':raise RuntimeError('Phone is not available')
    exists=adb('shell','sh','-c',f'"if [ -e {final} ]; then echo EXISTS; else echo ABSENT; fi"')
    if exists=='EXISTS':
        if adb('shell','sha256sum',final).split()[0]!=expected:raise ValueError('Different destination file already exists')
    elif exists=='ABSENT':
        adb('push',str(model),partial,timeout=1800)
        if adb('shell','sha256sum',partial).split()[0]!=expected or int(adb('shell','stat','-c','%s',partial))!=model.stat().st_size:
            raise ValueError('Phone temporary file failed verification')
        adb('shell','mv','-n',partial,final)
    else:raise ValueError('Unexpected phone existence check')
    if adb('shell','sha256sum',final).split()[0]!=expected or int(adb('shell','stat','-c','%s',final))!=model.stat().st_size:
        raise ValueError('Final phone file failed verification')
    result={'status':'COMPLETE','serial':SERIAL,'path':final,'bytes':model.stat().st_size,'sha256':expected,
            'verified_at':now(),'physical_device_inference':'NOT_RUN'}
    write(release/'phone_transfer.json',result)
    return result

def totals(cases):
    return {key:sum(r[key] is True for r in cases) for key in ('passed','json_valid','contract_valid','params_exact','reply_clock_correct')}

def report(deliveries, delivery_error=None):
    frozen=freeze_inputs()
    baseline=read(WORK/'baseline_evaluation/report.json')
    baseline_primary={r['case_id']:r for r in baseline['cases'][:231]}
    final={'completed_at':now(),'models':{},'protected_files_verified':len(frozen['files']),
           'phone_delivery_error':delivery_error,'physical_device_inference':'NOT_RUN'}
    lines=['# Компактные календарные модели на данных V12.63','',
        'Собраны отдельные Qwen3-1.7B и Qwen3.5-2B. Это новые адаптеры на официальных исходных весах. '
        'Существующие правила, русские примеры и Android-код сохранены.','',
        '## Результаты одинаковой проверки','',
        'Регрессионная проверка содержит 231 ранее использованный запрос и 66 дополнительных вариантов с фактическим '
        'системным сообщением и ChatML приложения. Это не новый независимый слепой набор. '
        'Ответы оценивались без JSON-грамматики и без исправления результата.','',
        '| Модель | Размер Q4_K_M | Полностью верно, 231 | Точные параметры, 231 | JSON, 297 | Контракт, 297 |',
        '|---|---:|---:|---:|---:|---:|']
    b=totals(baseline['cases']);bp=totals(baseline['cases'][:231])
    lines.append(f'| V12.63 4B | 2,50 ГБ | {bp["passed"]}/231 | {bp["params_exact"]}/231 | {b["json_valid"]}/297 | {b["contract_valid"]}/297 |')
    details=[]
    for key,name in NAMES.items():
        training=read(WORK/key/'training.json')
        artifact=read(WORK/key/'release/gguf_manifest.json')['model']
        evaluation=read(WORK/(key+'_evaluation')/'report.json')
        selected=read(WORK/key/'selection.json')
        t=totals(evaluation['cases']);p=totals(evaluation['cases'][:231])
        regressions=[r['case_id'] for r in evaluation['cases'][:231] if baseline_primary[r['case_id']]['passed'] and not r['passed']]
        gains=[r['case_id'] for r in evaluation['cases'][:231] if not baseline_primary[r['case_id']]['passed'] and r['passed']]
        lines.append(f'| {name} | {artifact["bytes"]/1e9:.2f} ГБ | {p["passed"]}/231 | {p["params_exact"]}/231 | {t["json_valid"]}/297 | {t["contract_valid"]}/297 |')
        entry={'artifact':artifact,'training':str(WORK/key/'training.json'),'selected_step':selected['best_step'],
            'primary_totals':p,'all_totals':t,'gained_ids':gains,'regressed_ids':regressions,
            'quality_status':evaluation['quality_status'],'phone_delivery':deliveries.get(key),
            'recommended_for_default_use':False}
        final['models'][key]=entry
        details += ['',f'## {name}','',f'- Файл: `{artifact["file"]}`.',
            f'- SHA-256: `{artifact["sha256"]}`.',
            f'- Обучение: {training["completed_optimizer_steps"]} шагов, три эпохи; выбран шаг {selected["best_step"]} по development-набору из 48 запросов.',
            f'- На общих 231 запросах: улучшений {len(gains)}, регрессий {len(regressions)} относительно V12.63.',
            '- Идентификаторы регрессий: '+(', '.join(regressions) or 'нет')+'.',
            '- Передача в Download: '+('SHA-256 и размер подтверждены.' if key in deliveries else 'не завершена; требуется доступ к телефону.')]
        for failure in (r for r in evaluation['cases'][:231] if not r['passed']):
            details += ['', 'Пример несовпадения: `'+failure['case_id']+'`.',
                '```json',json.dumps({'expected':failure.get('expected'),'actual':failure.get('actual')},ensure_ascii=False,indent=2),'```']
            break
    lines+=details+['','## Данные, воспроизводимость и ограничения','',
        '- 5 628 учебных записей, 694 validation-записи. Содержание сохранено побайтно. Новые русские шаблонные примеры не генерировались.',
        '- В обучении чередуется только транспорт сообщений: стандартный ChatML и точный ChatML приложения. Целевой ответ — исходный JSON и завершение сообщения.',
        '- Токенизация проверяется полностью без усечения; обучается только ответ assistant. Для каждого исходника закреплены ревизия, размеры, SHA-256 и лицензия Apache-2.0.',
        '- QLoRA NF4, FP16 на GTX 1080 Ti; LoRA rank 32, alpha 64, dropout 0,05; batch 1, накопление 16, lr 0,0001, три эпохи. Промежуточные адаптеры и состояние оптимизатора сохранены.',
        '- Выбор по среднему качеству шести intent на development-наборе, затем точным параметрам и времени в reply; итоговая проверка не использовалась для выбора.',
        '- GGUF: слияние с исходными весами, проверка конечных тензоров, F16, Q4_K_M. Qwen3.5 экспортируется как языковая модель без визуальной части.',
        '- Desktop inference: llama.cpp b10621, CPU, 12 потоков, context 512, максимум ответа 192, temperature 0, top_k 1, top_p 1, seed 20260825.',
        '- Проверка с Android-промптами выполнена на компьютере. Загрузка и генерация непосредственно в Android-приложении не проверены. Размер GGUF не равен расходу оперативной памяти.',
        '- Найденные ошибки не исправляются конвертацией. Автоматическая замена выбранной модели в приложении не выполнялась.',
        f'- Проверено сохранение {len(frozen["files"])} защищённых файлов. Полные сырые ответы, выбор адаптеров и хеши находятся в `build/calendar_small_models_20261003`.','']
    if delivery_error:lines+=['Ошибка передачи: '+delivery_error,'']
    REPORT.write_text('\n'.join(lines),encoding='utf-8')
    final['report']=str(REPORT)
    final['report_sha256']=digest(REPORT)
    final['execution_status']='COMPLETE' if not delivery_error else 'MODELS_COMPLETE_PHONE_PENDING'
    final['quality_status']='FAILURES_FOUND' if any(v['quality_status']!='PASS' for v in final['models'].values()) else 'PASS'
    write(WORK/'release_manifest.json',final)
    return final

def main():
    write(WORK/'workflow.json',{'status':'RUNNING','started_at':now(),'owner_pid':os.getpid()})
    # The user-approved first training and baseline evaluation were started
    # interactively. Adopt them without launching duplicate GPU/server jobs.
    with ThreadPoolExecutor(max_workers=1) as cpu:
        baseline_future=cpu.submit(wait_existing,'evaluate_small_models.py','baseline',WORK/'baseline_evaluation/report.json',
                                   lambda r:r.get('execution_status')=='COMPLETE')
        wait_existing('train_small_model.py','qwen3_1_7b',WORK/'qwen3_1_7b/training.json',lambda r:r.get('status')=='COMPLETE')
        baseline_future.result()
        first_future=cpu.submit(build_and_evaluate,'qwen3_1_7b')
        if not (WORK/'Qwen3.5-2B_source_lock.json').exists():raise RuntimeError('Qwen3.5 snapshot not yet verified')
        run('qwen35_2b_smoke',NEW_PYTHON,'train_small_model.py','--model','qwen35_2b','--mode','smoke')
        run('qwen35_2b_train',NEW_PYTHON,'train_small_model.py','--model','qwen35_2b','--mode','train')
        first_future.result()
        build_and_evaluate('qwen35_2b')
    deliveries={}
    error=None
    try:
        for key in NAMES:deliveries[key]=transfer(key)
    except Exception as exc:
        error=str(exc)
    result=report(deliveries,error)
    write(WORK/'workflow.json',{'status':result['execution_status'],'completed_at':now(),
                              'quality_status':result['quality_status'],'report':str(REPORT)})
    print(result['execution_status'])

if __name__=='__main__':
    try:main()
    except Exception:
        write(WORK/'workflow_error.json',{'status':'FAILED','time':now(),'traceback':traceback.format_exc()})
        raise
