import sys
import tempfile
import unittest
from pathlib import Path
from run_audit import run_process, successful


class AuditExitTests(unittest.TestCase):
    def test_completion_contract(self):
        success = "[rfaudit] COMPLETE checks=7 failures=0"
        self.assertTrue(successful(0, success))
        for code, log in [(1, success), (0, "BUILD SUCCESSFUL"), (0, success + "\n" + success),
                          (0, "[rfaudit] COMPLETE checks=7 failures=1"),
                          (0, "[rfaudit] COMPLETE checks=0 failures=0"),
                          (0, "[rfaudit] FAIL case\n" + success)]:
            self.assertFalse(successful(code, log), (code, log))

    def test_process_timeout(self):
        with tempfile.TemporaryDirectory() as temp:
            log = Path(temp) / "server.log"
            code = run_process([sys.executable, "-c", "import time; time.sleep(30)"], log, 0.2)
            self.assertEqual(code, 124)
            self.assertFalse(successful(code, log.read_text()))


if __name__ == "__main__":
    unittest.main()
