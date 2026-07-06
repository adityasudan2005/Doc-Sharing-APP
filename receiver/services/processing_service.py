import os
import subprocess
from PIL import Image, ImageOps
from receiver.config import settings

def process_uploaded_image(original_path: str, filename: str):
    """
    Background worker that runs post-processing tasks on the uploaded file:
    - Reads EXIF tags and transposes/rotates a copy of the image.
    - Saves the copy to the processed folder.
    - Automatically opens the processed file on the laptop if auto_open config is enabled.
    """
    try:
        # Define paths
        processed_dir = os.path.join(os.path.dirname(__file__), "..", settings.processed_folder)
        os.makedirs(processed_dir, exist_ok=True)
        processed_path = os.path.abspath(os.path.join(processed_dir, filename))
        
        # Open image using PIL
        with Image.open(original_path) as img:
            # ImageOps.exif_transpose automatically rotates/mirrors the image according to its EXIF tags
            processed_img = ImageOps.exif_transpose(img)
            
            # Save the processed image (preserve original quality, e.g., keep JPEG format/quality high)
            processed_img.save(processed_path, quality=95, subsampling=0)
            
        print(f"[PROCESS] Rotated working copy saved to: {processed_path}")
        
        # Auto-open if enabled
        if settings.auto_open:
            auto_open_image(processed_path)
            
    except Exception as e:
        print(f"[PROCESS ERROR] Failed to process image {filename}: {e}")

def auto_open_image(file_path: str):
    """Opens the image file in the default system viewer (Windows native supported)."""
    try:
        if hasattr(os, "startfile"):
            os.startfile(file_path)
            print(f"[PROCESS] Auto-opened {file_path} in default system viewer")
        else:
            # Fallback for other platforms
            import sys
            if sys.platform.startswith("darwin"):
                subprocess.call(["open", file_path])
            else:
                subprocess.call(["xdg-open", file_path])
            print(f"[PROCESS] Auto-opened {file_path} via subprocess")
    except Exception as e:
        print(f"[PROCESS ERROR] Could not auto-open file {file_path}: {e}")
