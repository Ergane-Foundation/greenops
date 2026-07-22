from fastapi import FastAPI, Query
import httpx
import asyncio
import os
from datetime import datetime, timezone
from typing import Optional

from forecast import (
    FORECAST_HORIZON_HOURS,
    FORECAST_RESOLUTION_MINUTES,
    parse_electricity_maps,
    simulated_forecast,
)

app = FastAPI()

ELECTRICITY_MAPS_TOKEN = os.getenv("ELECTRICITY_MAPS_TOKEN", "")
CARBON_THRESHOLD = int(os.getenv("CARBON_THRESHOLD_GCO2", "400"))
ZONE = os.getenv("ELECTRICITY_ZONE", "IN-NO")
FORECAST_POLL_SECONDS = int(os.getenv("FORECAST_POLL_SECONDS", "900"))

current_state = {
    "carbon_intensity": 0,
    "zone": ZONE,
    "grid_status": "UNKNOWN",
    "last_updated": None
}

forecast_state = {
    "zone": ZONE,
    "resolution_minutes": FORECAST_RESOLUTION_MINUTES,
    "horizon_hours": FORECAST_HORIZON_HOURS,
    "source": "simulated",
    "last_updated": None,
    "points": []
}

@app.get("/telemetry/status")
async def get_status(simulate: Optional[str] = Query(None)):
    if simulate == "dirty":
        return {**current_state, "grid_status": "DIRTY", "carbon_intensity": 850}
    if simulate == "clean":
        return {**current_state, "grid_status": "GREEN", "carbon_intensity": 120}
    return current_state

def _offset_points(offset_gco2: int):
    return [{**p, "carbon_intensity": max(0, p["carbon_intensity"] + offset_gco2)}
            for p in forecast_state["points"]]


@app.get("/telemetry/forecast")
async def get_forecast(simulate: Optional[str] = Query(None)):
    if simulate == "dirty":
        return {**forecast_state, "points": _offset_points(450)}
    if simulate == "clean":
        return {**forecast_state, "points": _offset_points(-200)}
    return forecast_state

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
            print("[Telemetry] No API token set, using simulated mode only")
        await asyncio.sleep(60)

async def poll_forecast():
    while True:
        if ELECTRICITY_MAPS_TOKEN:
            try:
                async with httpx.AsyncClient() as client:
                    resp = await client.get(
                        f"https://api.electricitymap.org/v3/carbon-intensity/forecast?zone={ZONE}",
                        headers={"auth-token": ELECTRICITY_MAPS_TOKEN},
                        timeout=10
                    )
                    if resp.status_code == 200:
                        points = parse_electricity_maps(resp.json())
                        if points:
                            forecast_state["points"] = points
                            forecast_state["source"] = "electricity-maps"
                            forecast_state["last_updated"] = datetime.now(timezone.utc).isoformat()
                        else:
                            print("[Telemetry] Forecast response held no usable points")
                    else:
                        print(f"[Telemetry] Forecast returned HTTP {resp.status_code}, keeping simulated")
            except Exception as e:
                print(f"[Telemetry] Forecast error: {e}")
        else:
            forecast_state["points"] = simulated_forecast(ZONE)
            forecast_state["source"] = "simulated"
            forecast_state["last_updated"] = datetime.now(timezone.utc).isoformat()
        await asyncio.sleep(FORECAST_POLL_SECONDS)

@app.on_event("startup")
async def startup_event():
    forecast_state["points"] = simulated_forecast(ZONE)
    forecast_state["source"] = "simulated"
    forecast_state["last_updated"] = datetime.now(timezone.utc).isoformat()
    asyncio.create_task(poll_carbon_intensity())
    asyncio.create_task(poll_forecast())
