import os
os.environ["AI_FORGE_MOCK"]="true"
os.environ["AI_FORGE_API_TOKEN"]="ci-test-token"
from fastapi.testclient import TestClient
from ai_forge.main import app

def test_auth():
    client=TestClient(app)
    assert client.get("/health").status_code==200
    assert client.get("/v1/status").status_code==401
    response=client.get("/v1/status",headers={"Authorization":"Bearer ci-test-token"})
    assert response.status_code==200
    assert response.json()["configured_agents"]["GPT"] is True

def test_council_mock():
    client=TestClient(app)
    response=client.post("/v1/council",headers={"Authorization":"Bearer ci-test-token"},json={"task":"Assess this test build.","context":{"test":True}})
    assert response.status_code==200
    assert response.json()["successful_agents"]==5
