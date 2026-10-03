"""Regression checks for missing clock grades and per-example batch weighting."""
import unittest
import torch
import torch.nn.functional as F
from small_models_v2_common import selection_score, completion_loss
from v12_63_evaluation import grade

class CompactTrainingRepairTests(unittest.TestCase):
    def test_actual_chat_grade_has_no_clock_score(self):
        case={'id':'chat','suite':'repair','system':'','user':'Привет',
              'expected':{'intent':'chat','reply':'Привет.','params':{}},'clocks':[],
              'clock_phrases':[],'prompt_format':'android_chatml'}
        result=grade(case,'{"intent":"chat","reply":"Привет.","params":{}}')
        self.assertIsNone(result['reply_clock_correct'])
        self.assertEqual(selection_score([case],[result]),[1.0,1.0,0])

    def test_unparseable_response_does_not_crash_selection(self):
        case={'id':'bad','suite':'repair','system':'','user':'Привет',
              'expected':{'intent':'chat','reply':'Привет.','params':{}},'clocks':[],
              'clock_phrases':[],'prompt_format':'android_chatml'}
        result=grade(case,'<think>')
        self.assertEqual(selection_score([case],[result]),[0.0,0.0,0])

    def test_padding_and_unequal_answer_lengths_preserve_row_weight_and_gradients(self):
        torch.manual_seed(17)
        logits=torch.randn(2,6,11,requires_grad=True)
        labels=torch.tensor([[-100,-100,3,4,5,6],[-100,-100,7,8,-100,-100]])
        loss=completion_loss(logits,labels)
        individual=(F.cross_entropy(logits[0,:-1,:],labels[0,1:],ignore_index=-100)
                    +F.cross_entropy(logits[1,:-1,:],labels[1,1:],ignore_index=-100))/2
        self.assertTrue(torch.allclose(loss,individual,atol=1e-7))
        actual=torch.autograd.grad(loss,logits,retain_graph=True)[0]
        expected=torch.autograd.grad(individual,logits)[0]
        self.assertTrue(torch.allclose(actual,expected,atol=1e-7))

    def test_empty_completion_is_rejected(self):
        with self.assertRaises(ValueError):
            completion_loss(torch.zeros(1,3,7),torch.full((1,3),-100))

if __name__=='__main__':unittest.main()
