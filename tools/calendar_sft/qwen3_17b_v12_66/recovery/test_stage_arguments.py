import unittest
from continue_evaluations import resolve_stage


class StageArgumentTests(unittest.TestCase):
    def test_f16_has_its_own_format_arguments(self):
        self.assertEqual(resolve_stage('f16_evaluation'), ('release_evaluation.py', ['--format', 'f16']))

    def test_q4_has_its_own_format_arguments(self):
        self.assertEqual(resolve_stage('q4_evaluation'), ('release_evaluation.py', ['--format', 'q4']))

    def test_only_comparison_uses_compare(self):
        self.assertEqual(resolve_stage('quantization_comparison'), ('release_evaluation.py', ['--compare']))
        self.assertEqual(resolve_stage('final_report'), ('final_report.py', []))

    def test_training_and_build_cannot_be_replayed(self):
        for name in ('training', 'gguf_build', 'release_evaluation.py'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                resolve_stage(name)


if __name__ == '__main__':
    unittest.main()
