from fastapi import FastAPI, Query
import httpx
import asyncio
import os
from typing import Optional

app = FastAPI()

ELECTRICITY_MAPS_TOKEN = os.getenv("ELECTRICITY_MAPS_TOKEN", "")
CARBON_THRESHOLD = int(os.getenv("CARBON_THRESHOLD_GCO2", "400"))
ZONE = os.getenv("ELECTRICITY_ZONE", "IN-NO")

current_state = {
    "carbon_intensity": 0,
    "zone": ZONE,
    "grid_status": "UNKNOWN",
    "last_updated": None
}

@app.get("/telemetry/status")
async def get_status(simulate: Optional[str] = Query(None)):
    if simulate == "dirty":
        return {**current_state, "grid_status": "DIRTY", "carbon_intensity": 850}
    if simulate == "clean":
        return {**current_state, "grid_status": "GREEN", "carbon_intensity": 120}
    return current_state

@app.get("/health")
async def health():
    return {"status": "ok"}

async def poll_carbon_intensity():
    while True:
        if ELECTRICITY_MAPS_TOKEN:
            try:
                async with httpx.AsyncClient() as client:
                    resp = await client.get(
                        f"https://api.electricitymap.org/v3/carbon-intensity/latest?zone={ZONE}",
                        headers={"auth-token": ELECTRICITY_MAPS_TOKEN},
                        timeout=10
                    )
                    data = resp.json()
                    intensity = data.get("carbonIntensity", 0)
                    current_state["carbon_intensity"] = intensity
                    current_state["grid_status"] = "DIRTY" if intensity > CARBON_THRESHOLD else "GREEN"
                    current_state["last_updated"] = data.get("datetime")
            except Exception as e:
                print(f"[Telemetry] Error: {e}")
        else:
            print("[Telemetry] No API token set — using simulated mode only")
        await asyncio.sleep(60)

@app.on_event("startup")
async def startup_event():
    asyncio.create_task(poll_carbon_intensity())