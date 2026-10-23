"""Never relabel assistant's visual chart descriptions as human expert truth."""
import json
from pathlib import Path

def test_visual_chart_notes_are_nonhuman_nontrading_and_complete():
    root=Path(__file__).parents[1]
    data=json.loads((root/"docs/evidence/williams_visual_reading_30_h1_nonhuman_20261023.json").read_text())
    rows=data["records"]
    assert len(rows)==30 and len({(r["symbol"],r["frame"],r["as_of"]) for r in rows})==30
    assert len({r["symbol"] for r in rows})==5
    assert all(r["annotation_origin"]=="ASSISTANT_VISUAL_READING_NOT_HUMAN_EXPERT" for r in rows)
    assert data["independent_human_annotations"]==0
    assert all(not r["eligible_for_independent_expert_accuracy"] and not r["completed_w5_proven"]
               and not r["can_place_orders"] and not r["can_close"] and not r["can_reverse"] for r in rows)
    assert all(len(r["review_ru"])>=35 for r in rows)
