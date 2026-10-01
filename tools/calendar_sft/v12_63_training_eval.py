"""Choose an adapter on development generations, never on the blind holdout."""
from __future__ import annotations
import json
from pathlib import Path
import shutil
from transformers import TrainerCallback
from prepare_v12_63 import read, write, digest
from v12_63_evaluation import case_from_row, grade, summary

class SemanticSelection(TrainerCallback):
    def __init__(self, tokenizer, dev_file, output, final_step):
        self.tokenizer, self.output = tokenizer, Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.dev_file = Path(dev_file)
        self.dev_sha = digest(self.dev_file)
        self.cases = [case_from_row(r, r.get('case_id', f'legacy_{i:03}'),
                     'clock' if r.get('category') == 'v12_63_dev' else 'legacy_' + json.loads(r['messages'][2]['content'])['intent'])
                     for i,r in enumerate(read(self.dev_file))]
        self.steps = {max(1, final_step//4), max(1, final_step//2), max(1, 3*final_step//4), final_step}
        self.results = []
        self.baseline_groups = None
        self.best = None
        self.best_step = None

    def evaluate(self, model, step):
        import torch
        if digest(self.dev_file) != self.dev_sha:
            raise ValueError('frozen development set changed during training')
        folder = self.output / f'semantic-{step:04}'
        folder.mkdir(exist_ok=False)
        training = model.training
        model.eval()
        records = []
        device = next(model.parameters()).device
        with torch.random.fork_rng(devices=[torch.cuda.current_device()]), torch.inference_mode():
            for case in self.cases:
                ids = self.tokenizer.apply_chat_template([
                    {'role':'system','content':case['system']}, {'role':'user','content':case['user']}],
                    tokenize=True, add_generation_prompt=True, return_tensors='pt').to(device)
                generated = model.generate(input_ids=ids, attention_mask=torch.ones_like(ids), max_new_tokens=192,
                    do_sample=False, temperature=None, top_p=None, top_k=None, repetition_penalty=1.0,
                    pad_token_id=self.tokenizer.eos_token_id, eos_token_id=self.tokenizer.eos_token_id, use_cache=True)
                raw = self.tokenizer.decode(generated[0,ids.shape[1]:].tolist(), skip_special_tokens=True).strip()
                result = grade(case, raw)
                records.append(result)
                write(folder / (case['id'] + '.json'), result)
        if training:
            model.train()
        report = summary(records)
        if step == 0:
            self.baseline_groups = report['groups']
        eligible = step > 0 and all(v['params_exact'] >= self.baseline_groups[k]['params_exact']
                     for k,v in report['groups'].items() if k.startswith('legacy_'))
        clock = report['groups']['clock']
        score = (sum(v['passed'] for v in report['groups'].values()), clock['params_exact'], clock['reply_clock_correct'])
        baseline_clock = self.baseline_groups['clock']
        eligible = eligible and clock['params_exact'] >= baseline_clock['params_exact'] and clock['reply_clock_correct'] >= baseline_clock['reply_clock_correct']
        report.update(step=step, eligible=eligible, score=score, development_sha256=self.dev_sha)
        self.results.append(report)
        if step > 0:
            model.save_pretrained(folder / 'adapter', safe_serialization=True)
            self.tokenizer.save_pretrained(folder / 'adapter')
        if eligible and (self.best is None or score > self.best):
            self.best, self.best_step = score, step
        write(folder / 'summary.json', report)
        write(self.output / 'semantic_selection.json', {'status':'IN_PROGRESS','best_step':self.best_step,
              'best_score':self.best, 'evaluations':self.results, 'holdout_used':False})
        torch.cuda.empty_cache()

    def on_train_begin(self, args, state, control, model=None, **kwargs):
        self.evaluate(model, 0)

    def on_step_end(self, args, state, control, model=None, **kwargs):
        if state.global_step in self.steps:
            self.evaluate(model, state.global_step)

    def finalize(self):
        if self.best_step is None:
            raise RuntimeError('No checkpoint preserved baseline development params/reply; release selection is blocked by measured regression')
        source = self.output / f'semantic-{self.best_step:04}' / 'adapter'
        shutil.copytree(source, self.output / 'adapter')
        report = {'status':'COMPLETE','best_step':self.best_step, 'best_score':self.best,
                  'evaluations':self.results, 'holdout_used':False, 'adapter_sha256':digest(self.output / 'adapter/adapter_model.safetensors')}
        write(self.output / 'semantic_selection.json', report)
        return report
