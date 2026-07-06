import os
import json
from pydantic import BaseModel

CONFIG_FILE = os.path.join(os.path.dirname(__file__), "config.json")

class AppConfig(BaseModel):
    save_original: bool = True
    save_folder: str = "uploads"
    processed_folder: str = "processed"
    auto_open: bool = False
    port: int = 8000

def load_config() -> AppConfig:
    if not os.path.exists(CONFIG_FILE):
        return AppConfig()
    try:
        with open(CONFIG_FILE, "r") as f:
            data = json.load(f)
            return AppConfig(**data)
    except Exception as e:
        print(f"Error loading config: {e}. Using defaults.")
        return AppConfig()

def save_config(config: AppConfig):
    try:
        with open(CONFIG_FILE, "w") as f:
            json.dump(config.model_dump(), f, indent=2)
    except Exception as e:
        print(f"Error saving config: {e}")

# Global configuration instance
settings = load_config()

# Ensure folders exist
os.makedirs(os.path.join(os.path.dirname(__file__), settings.save_folder), exist_ok=True)
os.makedirs(os.path.join(os.path.dirname(__file__), settings.processed_folder), exist_ok=True)
