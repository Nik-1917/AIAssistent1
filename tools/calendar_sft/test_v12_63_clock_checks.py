"""Meaningful regression tests for the independent numerical audit boundary."""
import unittest
from prepare_v12_63 import relative_clocks, full_clocks, duration, check_variant
from prepare_v12_61 import compact_clock

class ClockChecks(unittest.TestCase):
    def test_quarter_is_not_half(self):
        self.assertEqual(relative_clocks('В четверть первого дня встреча.'), [(12,15)])
        self.assertEqual(relative_clocks('В половине первого ночи встреча.'), [(0,30)])
    def test_minutes_to_31_and_59(self):
        self.assertEqual(relative_clocks('Без двадцати девяти минут час дня.'), [(12,31)])
        self.assertEqual(relative_clocks('Без одной минуты двенадцать ночи.'), [(23,59)])
    def test_elapsed_minute_feminine(self):
        self.assertEqual(relative_clocks('В двадцать одну минуту второго дня.'), [(13,21)])
        self.assertEqual(relative_clocks('В двадцать две минуты третьего ночи.'), [(2,22)])
    def test_full_does_not_force_pm(self):
        self.assertEqual(full_clocks('В шесть часов пятнадцать минут.'), [(6,15)])
        self.assertEqual(full_clocks('В шесть часов пятнадцать минут вечера.'), [(18,15)])
    def test_compact_does_not_force_pm(self):
        self.assertEqual(compact_clock('Завтра в шесть пятнадцать встреча.'), (6,15))
        self.assertEqual(compact_clock('Завтра двадцать пять встреча.'), (20,5))
    def test_named_midday_and_midnight(self):
        self.assertEqual(relative_clocks('Без четверти двенадцать дня.'), [(11,45)])
        self.assertEqual(relative_clocks('Без четверти двенадцать ночи.'), [(23,45)])
    def test_no_guess_for_missing_daypart(self):
        self.assertEqual(relative_clocks('Завтра четверть первого.'), [])
        self.assertEqual(relative_clocks('Завтра без четверти час.'), [])
    def test_duration_not_whole_clock(self):
        self.assertEqual(relative_clocks('На час завтра в четыре часа утра осмотр.'), [(4,0)])
        self.assertEqual(duration('На час завтра в четыре часа утра осмотр.'), 60)
    def test_long_elapsed_input_accepted(self):
        self.assertEqual(relative_clocks('Пятьдесят пять минут первого дня.'), [(12,55)])
    def test_reject_wrong_minute_target(self):
        row={'messages':[{'content':'Сегодня дата и время:2026-10-01 (четверг) 10:20 Europe/Samara ответ JSON'},
                         {},{'content':'{"intent":"calendar_add","reply":"Обед завтра в половине первого.","params":{"title":"Обед","starts_at":"2026-10-02T12:30"}}'}]}
        with self.assertRaises(ValueError):
            check_variant('12:30', 'Завтра обед в четверть первого дня.', row)

if __name__ == '__main__':
    unittest.main()
