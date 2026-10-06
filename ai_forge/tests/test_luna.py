import os
os.environ["AI_FORGE_MOCK"]="true"
os.environ["AI_FORGE_API_TOKEN"]="ci-test-token"

from fastapi.testclient import TestClient

from ai_forge.main import app


def test_luna_free_chat():
    client = TestClient(app)
    response = client.post(
        "/v1/luna",
        headers={"Authorization": "Bearer ci-test-token"},
        json={"message": "Привет, Luna"},
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["mode"] == "FREE"
    assert payload["provider"] == "FREE-CORE"
    assert "Luna" in payload["answer"]


def test_luna_requires_auth():
    client = TestClient(app)
    response = client.post("/v1/luna", json={"message": "test"})
    assert response.status_code == 401
