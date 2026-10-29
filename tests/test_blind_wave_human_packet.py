"""Reviewer-only packet must never include production or proxy labels."""
import csv,json
from pathlib import Path
from tools.export_blind_human_wave_packet import export_packet

def test_human_packet_contains_thirty_unlabelled_cases_and_no_predictions(tmp_path):
    entries=[];src=tmp_path/"source";src.mkdir()
    for i in range(30):
        symbol=["BTCUSDT","ETHUSDT","BNBUSDT","SOLUSDT","XRPUSDT"][i//6]
        asof=1731931200000+(i%6)*172800000
        (src/f"{symbol}_{asof}.png").write_bytes(b"PNG_TEST_DUMMY")
        entries.append({"symbol":symbol,"frame":"1h","as_of":asof,
             "prefix_sha256":"a"*64,"source_window_sha256":"b"*64,
             "reference_label":"MOTIVE_FIVE","model_label":"CORRECTION_ABC"})
    manifest=tmp_path/"raw.json"
    manifest.write_text(json.dumps({"cases":entries}))
    packet=tmp_path/"packet"
    assert export_packet(manifest,src,packet)["labels_in_packet"]==0
    f=list(csv.DictReader((packet/"blank_annotator_form.csv").open()))
    assert len(f)==30
    assert all(r["expert_label"]==r["reviewer_id"]==r["confidence_0_1"]=="" for r in f)
    contents="".join(p.read_text(errors="ignore") for p in packet.rglob("*") if p.is_file())
    assert "reference_label" not in contents and "model_label" not in contents


def test_human_packet_90_snapshot_manifest_preserves_only_blank_review_fields(tmp_path):
    source_dir=tmp_path/"images";source_dir.mkdir()
    entries=[]
    for j in range(90):
        symbol=f"PAIR{j//6}USDT"
        asof=1731931200000+(j%6)*172800000
        (source_dir/f"{symbol}_{asof}.png").write_bytes(b"PNG_TEST_DUMMY")
        entries.append({"symbol":symbol,"frame":"1h","as_of":asof,
                        "prefix_sha256":"c"*64,"source_window_sha256":"d"*64})
    manifest=tmp_path/"manifest_90.json"
    manifest.write_text(json.dumps({"schema":"WILLIAMS_BLIND_WAVE_90_V1",
                                    "snapshots":entries}),encoding="utf-8")
    output=tmp_path/"packet_90"
    result=export_packet(manifest,source_dir,output)
    assert result["cases"]==90
    assert result["labels_in_packet"]==0
    assert not result["includes_model_outputs"]
    assert len(list((output/"charts").glob("*.png")))==90
    with (output/"blank_annotator_form.csv").open(encoding="utf-8") as f:
        rows=list(csv.DictReader(f))
    assert len(rows)==90
    assert all(not r["expert_label"] and not r["reviewer_id"] and
               len(r["prefix_sha256"])==64 for r in rows)
    assert "90 fixed" in (output/"INSTRUCTIONS_FOR_INDEPENDENT_ANNOTATOR.md").read_text()
