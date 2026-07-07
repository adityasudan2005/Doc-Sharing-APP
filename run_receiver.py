import os
import sys

def start_server():
    """Starts the FastAPI server."""
    print("[SERVER START] Launching FastAPI receiver on host 0.0.0.0:8000...")
    
    # Ensure current directory is on python path
    current_dir = os.path.dirname(os.path.abspath(__file__))
    sys.path.insert(0, current_dir)
    
    try:
        import uvicorn
        from receiver.config import settings
        
        uvicorn.run("receiver.main:app", host="0.0.0.0", port=settings.port, reload=True)
    except ImportError:
        print("[ERROR] uvicorn or fastapi is not installed.")
        print("        Please run: pip install -r receiver/requirements.txt")
        sys.exit(1)

if __name__ == "__main__":
    start_server()
