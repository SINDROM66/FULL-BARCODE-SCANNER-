import os
import sys
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional, List

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ug_id_parser import parse_card, CardParseError

app = FastAPI(title="Uganda ID Barcode Parser API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class ParseRequest(BaseModel):
    payload: str

class FingerprintModel(BaseModel):
    finger_index: Optional[int] = None
    minutiae_count: Optional[int] = None
    minutiae_bytes: Optional[int] = None
    sealed_block_bytes: Optional[int] = None

class ParseResponse(BaseModel):
    surname: str
    given_name: str
    other_name: str
    full_name: str
    date_of_birth: str
    issue_date: str
    expiry_date: str
    nin: str
    sex: str
    card_number: str
    age: int
    is_expired: bool
    fingerprint: FingerprintModel
    warnings: List[str]

@app.post("/parse", response_model=ParseResponse)
async def parse_id(request: ParseRequest):
    try:
        record = parse_card(request.payload)
        return record.to_dict()
    except CardParseError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Internal error: {str(e)}")

@app.get("/health")
async def health():
    return {"status": "ok"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
