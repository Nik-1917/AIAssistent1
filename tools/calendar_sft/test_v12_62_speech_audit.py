"""Counterexamples the speech audit must reject, including minute grammar."""
import unittest
from audit_v12_62_reply_speech import check_clock


class SpeechAuditTest(unittest.TestCase):
    def test_canonical(self):
        cases = (("Едем в одну минуту первого.", "12:01"),
                 ("Едем в двадцать две минуты первого.", "12:22"),
                 ("Едем в четверть первого.", "12:15"),
                 ("Едем в половине первого.", "12:30"),
                 ("Едем без двадцати одной минуты час.", "12:39"),
                 ("Едем без десяти час.", "12:50"),
                 ("Едем без четверти двенадцать.", "23:45"),
                 ("Едем в двадцать три часа.", "23:00"))
        for text, clock in cases:
            with self.subTest(text=text):
                self.assertTrue(check_clock(text, clock))

    def test_wrong_clocks_and_inflections(self):
        cases = (("Едем в половине первого.", "12:15"),
                 ("Едем в две минут первого.", "12:02"),
                 ("Едем в двадцать один минуту первого.", "12:21"),
                 ("Едем без одной минут час.", "12:59"),
                 ("Едем без двадцати одной минут час.", "12:39"),
                 ("Едем в без двух минут час.", "12:58"),
                 ("Едем в четверть первого, в половине первого.", "12:15"),
                 ("Едем на тридцать минут.", "12:30"),
                 ("Едем в пятнадцать минут первого.", "12:15"))
        for text, clock in cases:
            with self.subTest(text=text):
                self.assertFalse(check_clock(text, clock))


if __name__ == "__main__":
    unittest.main()
