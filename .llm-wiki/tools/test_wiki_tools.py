"""Regression fixtures for wiki auditing and publication; no repository writes."""

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import audit
import publish_wiki


class WikiToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name).resolve()
        self.wiki = self.repo / '.llm-wiki'
        self.wiki.mkdir()
        self.source = self.repo / 'src' / 'Sample.java'
        self.source.parent.mkdir()
        self.source.write_text('class Sample { void run() {} }\n')
        self.page = self.wiki / 'index.md'
        self.page.write_text('---\ncommit: known\nsources: [src/]\n---\n')

    def git(self, repo, *args):
        if args[0] == 'ls-files':
            return '.llm-wiki/index.md\0'
        if args[0] == 'rev-parse':
            if 'missing^{commit}' in args:
                raise subprocess.CalledProcessError(1, args)
            return 'known\n'
        return ''

    def findings(self):
        with patch.object(audit, 'git', side_effect=self.git):
            return audit.audit(self.repo)

    def test_unknown_commit_is_a_finding_and_fenced_metadata_is_ignored(self):
        self.page.write_text('---\ncommit: missing\nsources: [src/]\n---\n'
                             '```yaml\ncommit: known\nsources: [elsewhere/]\n```\n')
        self.assertEqual(len(self.findings()), 1)
        self.assertIn('unknown commit', self.findings()[0])

    def test_source_links_symbols_and_legacy_line_citations(self):
        with self.page.open('a') as out:
            out.write('[Sample#run](../src/Sample.java)\n'
                      '[`Sample#gone`](../src/Sample.java)\n'
                      '[Missing](../src/Gone.java)\n'
                      '`Sample.java,57-67`\n'
                      '```md\n[[not-a-page]]\n[Missing](../missing)\n```\n')
        findings = self.findings()
        self.assertEqual(len(findings), 3)
        self.assertTrue(any('missing symbol' in x for x in findings))
        self.assertTrue(any('dead source link' in x for x in findings))
        self.assertTrue(any('line citation' in x for x in findings))

    def test_committed_and_working_tree_drift_are_separate(self):
        def changed(repo, *args):
            if args[0] == 'diff':
                return 'src/Old.java\0' if 'known' in args else 'src/New.java\0'
            return self.git(repo, *args)
        with patch.object(audit, 'git', side_effect=changed):
            findings = audit.audit(self.repo)
        self.assertEqual(len(findings), 2)
        self.assertIn('committed drift', findings[0])
        self.assertIn('src/Old.java', findings[0])
        self.assertIn('working-tree drift', findings[1])
        self.assertIn('src/New.java', findings[1])

    def test_untracked_pages_do_not_pollute_audit(self):
        (self.wiki / 'scratch.md').write_text('[[missing]]\n')
        self.assertEqual(self.findings(), [])

    def test_publication_rewrites_symbol_links_but_preserves_external_and_fences(self):
        body = ('[Sample#run](../src/Sample.java)\n'
                '[External](https://example.org)\n'
                '```md\n[Sample#run](../src/Sample.java)\n```\n')
        with patch.object(publish_wiki, 'REPO', self.repo):
            rendered = publish_wiki.link_code_refs(body, 'https://example.org/blob/abc', self.page)
        self.assertEqual(rendered, '[Sample#run](https://example.org/blob/abc/src/Sample.java)\n'
                                  '[External](https://example.org)\n'
                                  '```md\n[Sample#run](../src/Sample.java)\n```\n')

    def test_publication_does_not_nest_links_or_close_long_fences_early(self):
        body = ('[`src/Sample.java`](../src/Sample.java)\n'
                '````md\n```java\n`src/Sample.java`\n```\n````\n')
        with patch.object(publish_wiki, 'REPO', self.repo):
            rendered = publish_wiki.link_code_refs(body, 'https://example.org/blob/abc', self.page)
        self.assertEqual(rendered, '[`src/Sample.java`](https://example.org/blob/abc/src/Sample.java)\n'
                                  '````md\n```java\n`src/Sample.java`\n```\n````\n')


if __name__ == '__main__':
    unittest.main()
