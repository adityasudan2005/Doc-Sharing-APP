from fastapi import APIRouter
from receiver.config import settings, AppConfig, save_config

router = APIRouter()

@router.get("/ping")
async def ping():
    """Endpoint for connectivity test from the Android app."""
    return {"status": "ok"}

@router.get("/config")
async def get_config():
    """Endpoint to inspect current receiver settings."""
    return settings

@router.post("/config")
async def update_config(new_config: AppConfig):
    """Endpoint to update configurations dynamically."""
    global settings
    for key, value in new_config.model_dump().items():
        setattr(settings, key, value)
    save_config(settings)
    return {"success": True, "message": "Configuration updated successfully", "config": settings}
