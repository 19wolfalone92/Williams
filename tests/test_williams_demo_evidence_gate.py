"""A happy synthetic timeline proves only the validator, not physical device execution."""
from williams_demo_evidence_gate import validate_demo_device_evidence

def example():
    t=1_800_000_000_000
    def row(i,type,**kw):return {"at_ms":t+i*1000,"type":type,**kw}
    return {"environment":"BINANCE_USDM_FUTURES_DEMO","origin":"PHYSICAL_ANDROID_EXPORT",
     "apk_sha256":"c"*64,"events":[
        row(0,"APK_INSTALLED"),row(1,"KEYSTORE_SAVED"),row(2,"ENGINE_RUNNING"),
        row(3,"DEMO_ACCOUNT_READ",source="SIGNED_DEMO_ACCOUNT_SNAPSHOT"),
        row(4,"ENTRY_FILL_REPORTED",symbol="BTCUSDT",qty=0.1,exchange_order_id="FILL001"),
        row(5,"STOP_ACK_REPORTED",symbol="BTCUSDT",qty=0.1,order_id="STOP001",
            status="NEW",reduce_only=True),
        row(6,"PROCESS_KILLED"),row(7,"BOOT_COMPLETED"),
        row(8,"KEYSTORE_RESTORED",same_identity=True),
        row(9,"ENGINE_RECOVERED",state="RUNNING"),
        row(10,"POSITION_RECONCILED",source="SIGNED_DEMO_ACCOUNT_SNAPSHOT",symbol="BTCUSDT",qty=0.1),
        row(11,"STOP_RECONFIRMED",symbol="BTCUSDT",qty=0.1,
            order_id="STOP001",status="NEW",reduce_only=True),
        row(12,"POSITION_FLAT",exit_reason="EXCHANGE_STOP_FILL")
    ]}
def test_complete_fake_trace_is_never_called_device_accepted():
    r=validate_demo_device_evidence(example())
    assert r["status"]=="STRUCTURAL_PASS_REQUIRES_PHYSICAL_EXCHANGE_ATTESTATION",r
    assert r["full_trade_lifecycle_certified"] is False and r["can_enable_live"] is False

def test_missing_protection_or_zero_quantity_fails_closed():
    d=example()
    d["events"]=[e for e in d["events"] if e["type"]!="STOP_ACK_REPORTED"]
    assert "MISSING_DEVICE_LIFECYCLE_STEPS" in validate_demo_device_evidence(d)["errors"]
    d=example()
    for e in d["events"]:
        if e["type"]=="STOP_ACK_REPORTED": e["qty"]=0.01
    assert "INSUFFICIENT_EXCHANGE_PROTECTIVE_QUANTITY" in validate_demo_device_evidence(d)["errors"]

def test_reboot_lost_keys_and_stopped_engine_not_accepted():
    d=example()
    for e in d["events"]:
        if e["type"]=="KEYSTORE_RESTORED":e["same_identity"]=False
        if e["type"]=="ENGINE_RECOVERED":e["state"]="STOPPED"
    assert "API_IDENTITY_NOT_PRESERVED_AFTER_REBOOT" in validate_demo_device_evidence(d)["errors"]
    assert "ENGINE_NOT_RUNNING_AFTER_REBOOT" in validate_demo_device_evidence(d)["errors"]

def test_unexpected_exit_and_diagnostic_secrets_are_blockers():
    d=example()
    for e in d["events"]:
        if e["type"]=="POSITION_FLAT": e["exit_reason"]="OPPOSING_WISE_MAN"
        if e["type"]=="KEYSTORE_SAVED":e["api_secret"]="MUST_NOT_LEAK"
    result=validate_demo_device_evidence(d)
    assert "EXIT_REASON_NOT_VERIFIED_STOP_OR_USER" in result["errors"]
    assert "SECRET_FIELD_FOUND_IN_DIAGNOSTIC" in result["errors"]

def test_wrong_environment_not_accepted():
    d=example();d["environment"]="LIVE"
    assert "WRONG_OR_UNKNOWN_EXCHANGE_ENVIRONMENT" in validate_demo_device_evidence(d)["errors"]
