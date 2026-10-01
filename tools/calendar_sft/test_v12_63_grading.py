"""Guard against accepting good wording with incorrect parameters, or vice versa."""
import json
import unittest
from v12_63_evaluation import grade

class GradingChecks(unittest.TestCase):
    def setUp(self):
        self.case={'id':'test','suite':'test','system':'test','user':'test',
            'expected':{'intent':'calendar_add','params':{'title':'Поездка','starts_at':'2026-10-02T12:15'}},
            'clocks':['12:15'],'clock_phrases':[]}
    def output(self, reply, time='2026-10-02T12:15'):
        return json.dumps({'intent':'calendar_add','reply':reply,'params':{'title':'Поездка','starts_at':time}},ensure_ascii=False)
    def test_correct_quarter(self):
        self.assertTrue(grade(self.case,self.output('Поездка в четверть первого.'))['passed'])
    def test_half_reply_with_quarter_params_fails(self):
        r=grade(self.case,self.output('Поездка в половине первого.'))
        self.assertTrue(r['params_exact']);self.assertFalse(r['passed'])
    def test_wrong_day_with_correct_reply_fails(self):
        r=grade(self.case,self.output('Поездка в четверть первого.','2026-10-03T12:15'))
        self.assertTrue(r['reply_clock_correct']);self.assertFalse(r['passed'])
    def test_wrong_daypart_cannot_hide_behind_modulo_reply(self):
        r=grade(self.case,self.output('Поездка в четверть первого.','2026-10-02T00:15'))
        self.assertFalse(r['params_exact']);self.assertFalse(r['passed'])
    def test_unknown_time_in_reply_rejected(self):
        self.case['expected']['params']={'title':'Поездка','date':'2026-10-02'}
        self.case['clocks']=[]
        raw=json.dumps({'intent':'calendar_add','reply':'Поездка в четверть первого.','params':self.case['expected']['params']},ensure_ascii=False)
        self.assertFalse(grade(self.case,raw)['passed'])
    def test_minutes_to_not_elapsed_twenty_one(self):
        self.case['clocks']=['12:31'];self.case['expected']['params']['starts_at']='2026-10-02T12:31'
        r=grade(self.case,self.output('Поездка в двадцать одну минуту первого.','2026-10-02T12:31'))
        self.assertTrue(r['params_exact']);self.assertFalse(r['passed'])

if __name__=='__main__':unittest.main()
