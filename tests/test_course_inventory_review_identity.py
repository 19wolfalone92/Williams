import hashlib
import sys
from types import SimpleNamespace

from tools.williams_master_inventory import inventory


def test_export_keeps_old_acquisition_and_new_review_ocr_as_distinct_identities(tmp_path, monkeypatch):
    pdf_bytes=b'pinned PDF fixture; rendering mocked'
    (tmp_path/'source.pdf').write_bytes(pdf_bytes)
    ocr_dir=tmp_path/'original-ocr'/'COURSE_EN'
    ocr_dir.mkdir(parents=True)
    original='Original acquisition OCR bytes'
    (ocr_dir/'0001.ocr.txt').write_text(original)
    new_hash=hashlib.sha256(b'New independently derived OCR bytes').hexdigest()
    page=SimpleNamespace(get_text=lambda:'',get_image_info=lambda:[])
    monkeypatch.setitem(sys.modules,'fitz',SimpleNamespace(open=lambda _: [page]))
    catalog=[{'id':'COURSE_EN','family':'COURSE','filename':'source.pdf','edition':'fixture',
              'pdf_pages':1,'sha256':hashlib.sha256(pdf_bytes).hexdigest()}]
    reviews={'COURSE_EN:1':{'content':'Manual first-pass paraphrase','rules':[],
             'verification_status':'REVIEWED_TEXT_ONLY','review_text_sha256':new_hash,
             'review_text_provenance':'COURSE_DERIVED_OCR_20261113_V1'}}
    row=inventory(tmp_path,catalog,reviews,tmp_path/'out',tmp_path/'original-ocr')[0]
    assert row['acquired_text_sha256']==hashlib.sha256(original.encode()).hexdigest()
    assert row['review_text_sha256']==new_hash
    assert row['review_text_sha256']!=row['acquired_text_sha256']
    assert row['review_text_provenance']=='COURSE_DERIVED_OCR_20261113_V1'
