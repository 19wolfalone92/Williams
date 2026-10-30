"""Reviewer-only packet must never include production or proxy labels."""
import csv,json
import pytest
from tools.score_blind_human_wave_review import selected_prefixes
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


def test_blind_review_packet_uses_only_checksum_pinned_closed_exchange_rows(tmp_path):
    selected=selected_prefixes()
    source=tmp_path/"image_source";source.mkdir()
    manifest_cases=[]
    for (symbol,frame,as_of),(_rows,prefix_sha,full_sha) in sorted(selected.items()):
        (source/f"{symbol}_{as_of}.png").write_bytes(b"PNG_TEST_DUMMY")
        manifest_cases.append({"symbol":symbol,"frame":frame,"as_of":as_of,
            "prefix_sha256":prefix_sha,"source_window_sha256":full_sha})
    manifest=tmp_path/"source.json"
    manifest.write_text(json.dumps({"cases":manifest_cases}),encoding="utf-8")
    output=tmp_path/"packet"
    result=export_packet(manifest,source,output,include_ohlc=True)
    assert result["causal_160_h1_csv_included"] is True
    csvs=sorted((output/"closed_ohlc_h1").glob("*.csv"))
    assert len(csvs)==30
    for filename in csvs:
        as_of=int(filename.stem.rsplit("_",1)[1])
        with filename.open(newline="",encoding="utf-8") as f:
            rows=list(csv.DictReader(f))
        assert len(rows)==160
        assert all(int(r["open_time_ms"])+3_600_000<=as_of for r in rows)
        assert all(int(b["open_time_ms"])-int(a["open_time_ms"])==3_600_000
                   for a,b in zip(rows,rows[1:]))
        assert all(r["open_time_utc"].endswith("+00:00") for r in rows)
    wrong=dict(manifest_cases[0],prefix_sha256="0"*64)
    manifest.write_text(json.dumps({"cases":[wrong,*manifest_cases[1:]]}),encoding="utf-8")
    with pytest.raises(ValueError,match="BLIND_REVIEW_CLOSED_OHLC_SHA_OR_TIME_MISMATCH"):
        export_packet(manifest,source,tmp_path/"bad_packet",include_ohlc=True)
