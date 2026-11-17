"""Reproduce a page-complete ACQUISITION inventory from owner's local PDFs.

No book text/images are exported. A page row is not a claim of understanding.
Manual semantic/visual receipts are supplied separately in review_notes.json.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inventory(source_dir, catalog, reviews, output_dir, ocr_dir=None):
    import fitz
    rows = []
    for source in catalog:
        path = source_dir / source['filename']
        if digest(path.read_bytes()) != source['sha256']:
            raise ValueError('SOURCE_BYTES_MISMATCH:' + source['id'])
        document = fitz.open(path)
        if len(document) != source['pdf_pages']:
            raise ValueError('SOURCE_PAGE_COUNT_MISMATCH:' + source['id'])
        for index, page in enumerate(document):
            number = index + 1
            raw = page.get_text()
            ocr = ocr_dir / source['id'] / f'{number:04d}.ocr.txt' if ocr_dir else None
            acquired = ocr.read_text() if ocr and ocr.is_file() else raw
            note = reviews.get(f"{source['id']}:{number}", {})
            # get_images() enumerates PDF RESOURCES. A shared resource list can
            # repeat the entire document's images on EVERY page. get_image_info
            # counts actual image placements on this specific page instead.
            placements = page.get_image_info()
            mentions = sorted(set(re.findall(r'(?:FIGURE|Рис\.?|Рисунок)\s*(\d+[.\-]\d+)', raw)))
            status = note.get('verification_status', 'OCR_ACQUIRED_UNVERIFIED' if ocr and ocr.is_file()
                              else 'TEXT_ACQUIRED_UNVERIFIED' if raw.strip() else 'IMAGE_ONLY_PENDING_OCR')
            rows.append({
                'source': source['family'], 'source_id': source['id'], 'edition': source['edition'],
                'source_sha256': source['sha256'], 'pdf_page': number,
                'printed_page': note.get('printed_page'), 'section': note.get('section'),
                'content': note.get('content', 'Acquisition record only; semantic review pending.'),
                'figures': note.get('figures', []), 'figure_mentions_unverified': mentions,
                'placed_raster_image_count': len(placements),
                'extracted_rules': note.get('rules', []),
                'uncertainties': note.get('uncertainties', ['Not fully reviewed against the page rendering.']),
                'verification_status': status,
                'text_layer_characters': len(raw), 'acquired_text_characters': len(acquired),
                'acquired_text_sha256': digest(acquired.encode()),
                'visual_review': note.get('visual_review', False),
                'independent_second_review': False, 'can_place_orders': False,
            })
            if 'page_kind' in note:
                rows[-1]['page_kind'] = note['page_kind']
            for field in ('review_text_sha256', 'review_text_provenance'):
                if field in note:
                    rows[-1][field] = note[field]
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / 'PRIMARY_SOURCE_PAGE_REGISTER.jsonl'
    target.write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows))
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--catalog', type=Path, default=Path('research/SOURCE_CATALOG.json'))
    parser.add_argument('--reviews', type=Path, default=Path('research/review_notes.json'))
    parser.add_argument('--output-dir', type=Path, default=Path('research'))
    parser.add_argument('--ocr-dir', type=Path)
    args = parser.parse_args()
    rows = inventory(args.source_dir, json.loads(args.catalog.read_text()),
                     json.loads(args.reviews.read_text()), args.output_dir, args.ocr_dir)
    print(json.dumps({'pages_inventoried': len(rows),
        'pages_semantically_reviewed': sum(r['verification_status'].startswith('REVIEWED') for r in rows),
        'whole_source_review_complete': all(r['verification_status'] == 'VERIFIED' for r in rows),
        'can_place_orders': False}))


if __name__ == '__main__':
    main()
