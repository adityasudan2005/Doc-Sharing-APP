import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from receiver.config import settings
from receiver.api.health import router as health_router
from receiver.api.upload import router as upload_router

app = FastAPI(
    title="Document Camera Receiver API",
    description="Backend API to receive and process documents sent from mobile device",
    version="1.0.0"
)

# Enable CORS for developer testing
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include routers
app.include_router(health_router, tags=["Health & Configuration"])
app.include_router(upload_router, tags=["Uploads"])

@app.get("/")
async def root():
    return {
        "app": "Document Camera Receiver",
        "status": "running",
        "save_folder": settings.save_folder,
        "processed_folder": settings.processed_folder
    }

if __name__ == "__main__":
    # Standard startup script
    print(f"Starting server on port {settings.port}...")
    uvicorn.run("receiver.main:app", host="0.0.0.0", port=settings.port, reload=True)
