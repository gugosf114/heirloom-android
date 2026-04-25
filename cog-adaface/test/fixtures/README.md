# Test fixtures

Empty by default. Run `bash test/download_fixtures.sh` to populate with
public-domain face photos from Wikimedia Commons.

| File | Subject | Source |
|---|---|---|
| `same_a.jpg` | Einstein, head shot | Wikimedia, PD |
| `same_b.jpg` | Einstein, 1921 (Schmutzer portrait) | Wikimedia, PD |
| `diff_a.jpg` | Niels Bohr | Wikimedia, PD |

Two distinct Einstein photos let the cosine test verify *generalization*
(not just deterministic embedding repeatability), since they're decades
apart with different photographers and lighting.

These files are gitignored — pull them with the script, don't commit.
