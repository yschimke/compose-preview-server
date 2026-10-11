# Regenerates `reference-vectors.json`, the matrices `test/qrEncode.test.ts` compares the viewer's
# QR encoder against, from an independent encoder: python-qrcode 8.2 (BSD), byte mode, with the
# version, level and mask pinned so the comparison is module for module.
#
#   pip install --target /tmp/qr qrcode==8.2
#   PYTHONPATH=/tmp/qr python3 test/fixtures/qr/reference.py > test/fixtures/qr/reference-vectors.json
#
# (segno is NOT a usable reference for this: in byte mode it appends a whole extra 0x00 codeword
# whenever the terminator already ends on a byte boundary, which is valid but not canonical.)
import json

import qrcode
from qrcode.constants import ERROR_CORRECT_L, ERROR_CORRECT_M
from qrcode.util import MODE_8BIT_BYTE, QRData

# (text, level, version, mask): every mask, both levels, a version per structural feature — one
# block, two blocks, version information (7+), the 16-bit length field (10+), mixed block lengths
# and several alignment rows (14, 22).
CASES = [
    ("HELLO", "M", 1, 0),
    ("https://preview.coo.ee/", "L", 2, 1),
    ("https://preview.coo.ee/preview/com.example.Card", "M", 4, 2),
    ("https://preview.coo.ee/preview/com.example.ui.ButtonsKt.FilledButtonPreview", "M", 5, 3),
    ("https://preview.coo.ee/preview/com.example.ui.ButtonsKt.FilledButtonPreview?theme=dark", "L", 7, 4),
    ("https://preview.coo.ee/preview/" + "com.example.ui.ButtonsKt.FilledButtonPreview?theme=dark&fontScale=1.3&locale=de", "M", 10, 5),
    ("https://preview.coo.ee/" + "x" * 300, "L", 14, 6),
    ("Größe 😀 " * 40, "M", 22, 7),
    # The mask the encoder CHOOSES for this text (`qrEncode.test.ts` asserts the choice too): the
    # full penalty picks 5. Counting a 1:1:3:1:1 run with light on both sides once instead of
    # twice under rule N3 picked 7 (review of #1556).
    ("https://preview.coo.ee/preview/com.example.Card?x=0-", "L", 3, 5),
]


def matrix(text, ecl, ver, mask):
    q = qrcode.QRCode(
        version=ver,
        error_correction=ERROR_CORRECT_L if ecl == "L" else ERROR_CORRECT_M,
        border=0,
        mask_pattern=mask,
    )
    q.add_data(QRData(text.encode("utf-8"), mode=MODE_8BIT_BYTE))
    q.make(fit=False)
    return ["".join("1" if dark else "0" for dark in row) for row in q.get_matrix()]


print(
    json.dumps(
        [
            {"text": t, "ecl": e, "version": v, "mask": m, "rows": matrix(t, e, v, m)}
            for t, e, v, m in CASES
        ],
        ensure_ascii=False,
        indent=0,
    )
)
