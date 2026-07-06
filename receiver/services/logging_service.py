import os
import time

LOG_FILE = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "transfer_log.txt"))

def log_transfer(filename: str, file_size: int, duration: float, client_mode: str):
    """
    Logs details of every image transfer to a shared text file and the console.
    """
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
    size_mb = file_size / (1024 * 1024)
    
    log_entry = (
        f"[{timestamp}] "
        f"File: {filename} | "
        f"Size: {size_mb:.2f} MB | "
        f"Transfer Time: {duration:.3f}s | "
        f"Mode: {client_mode.upper()}\n"
    )
    
    # Print to stdout/console
    print(f"\n[TRANSFER LOG] {log_entry.strip()}")
    
    # Write to local file
    try:
        with open(LOG_FILE, "a") as f:
            f.write(log_entry)
    except Exception as e:
        print(f"[LOG ERROR] Failed to write transfer log: {e}")
