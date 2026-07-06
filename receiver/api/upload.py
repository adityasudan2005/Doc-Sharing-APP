import os
import time
import uuid
import shutil
from fastapi import APIRouter, UploadFile, File, BackgroundTasks, Header
from receiver.config import settings
from receiver.services.processing_service import process_uploaded_image
from receiver.services.logging_service import log_transfer

router = APIRouter()

@router.post("/upload")
async def upload_image(
    background_tasks: BackgroundTasks,
    image: UploadFile = File(...),
    x_client_mode: str = Header("unknown")
):
    """
    Saves the original uploaded image file immediately, then pushes the 
    post-processing tasks (EXIF check/rotation/auto-open) to background worker.
    """
    start_time = time.time()
    
    # Create unique filename
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    short_uuid = uuid.uuid4().hex[:6].upper()
    file_ext = os.path.splitext(image.filename)[1] or ".jpg"
    unique_filename = f"doc_{timestamp}_{short_uuid}{file_ext}"
    
    # Save original photo (no modifications to original bytes)
    save_dir = os.path.join(os.path.dirname(__file__), "..", settings.save_folder)
    os.makedirs(save_dir, exist_ok=True)
    original_path = os.path.abspath(os.path.join(save_dir, unique_filename))
    
    # Read bytes and write to file
    file_size = 0
    try:
        with open(original_path, "wb") as buffer:
            # We copy in chunks to avoid high memory overhead
            while chunk := await image.read(1024 * 1024):
                buffer.write(chunk)
                file_size += len(chunk)
    except Exception as e:
        return {"success": False, "error": f"Failed to save file: {str(e)}"}
        
    duration = time.time() - start_time
    
    # Queue background task for EXIF rotation, optional opening, and detailed logging
    background_tasks.add_task(
        process_uploaded_image, 
        original_path=original_path, 
        filename=unique_filename
    )
    
    background_tasks.add_task(
        log_transfer,
        filename=unique_filename,
        file_size=file_size,
        duration=duration,
        client_mode=x_client_mode
    )
    
    return {
        "success": True,
        "filename": unique_filename,
        "size_bytes": file_size,
        "upload_time_sec": round(duration, 3),
        "client_mode": x_client_mode
    }
