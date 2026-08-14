from __future__ import annotations

import asyncio
import json
import logging
import os
import threading
from contextlib import asynccontextmanager
from typing import Any

import cv2
import numpy as np
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from paddleocr import PaddleOCR

LOGGER = logging.getLogger("videomind.paddleocr")
MAX_UPLOAD_BYTES = int(os.getenv("OCR_MAX_UPLOAD_BYTES", "20971520"))
MODEL: PaddleOCR | None = None
MODEL_ERROR = False
MODEL_LOCK = threading.Lock()
INFERENCE_LIMIT = asyncio.Semaphore(1)


def _initialize_model() -> None:
    global MODEL, MODEL_ERROR
    try:
        with MODEL_LOCK:
            if MODEL is None:
                MODEL = PaddleOCR(
                    device="cpu",
                    text_detection_model_name=os.getenv(
                        "OCR_DETECTION_MODEL", "PP-OCRv5_mobile_det"
                    ),
                    text_recognition_model_name=os.getenv(
                        "OCR_RECOGNITION_MODEL", "PP-OCRv5_mobile_rec"
                    ),
                    use_doc_orientation_classify=False,
                    use_doc_unwarping=False,
                    use_textline_orientation=False,
                    enable_mkldnn=False,
                    cpu_threads=1,
                )
        LOGGER.info("PaddleOCR CPU model is ready")
    except Exception:
        MODEL_ERROR = True
        LOGGER.exception("PaddleOCR model initialization failed")


@asynccontextmanager
async def lifespan(_: FastAPI):
    threading.Thread(target=_initialize_model, name="paddleocr-init", daemon=True).start()
    yield


app = FastAPI(title="VideoMind PaddleOCR Adapter", version="1.0.0", lifespan=lifespan)


@app.get("/health")
def health() -> JSONResponse:
    if MODEL_ERROR:
        return JSONResponse(status_code=503, content={"status": "failed"})
    if MODEL is None:
        return JSONResponse(status_code=503, content={"status": "starting"})
    return JSONResponse(content={"status": "healthy", "device": "cpu"})


def _payload(value: Any) -> Any:
    candidate = getattr(value, "json", value)
    if callable(candidate):
        candidate = candidate()
    if isinstance(candidate, str):
        return json.loads(candidate)
    return candidate


def collect_recognition(value: Any) -> tuple[list[str], list[float]]:
    texts: list[str] = []
    scores: list[float] = []

    def visit(node: Any) -> None:
        node = _payload(node)
        if isinstance(node, dict):
            rec_texts = node.get("rec_texts")
            rec_scores = node.get("rec_scores")
            if isinstance(rec_texts, list):
                score_values = rec_scores if isinstance(rec_scores, list) else []
                for index, text in enumerate(rec_texts):
                    normalized = str(text).strip()
                    if not normalized:
                        continue
                    score = score_values[index] if index < len(score_values) else 1.0
                    try:
                        confidence = max(0.0, min(1.0, float(score)))
                    except (TypeError, ValueError):
                        confidence = 1.0
                    texts.append(normalized)
                    scores.append(confidence)
                return
            for child in node.values():
                visit(child)
        elif isinstance(node, (list, tuple)):
            for child in node:
                visit(child)

    visit(value)
    deduplicated_texts: list[str] = []
    deduplicated_scores: list[float] = []
    seen: set[str] = set()
    for text, score in zip(texts, scores):
        if text not in seen:
            seen.add(text)
            deduplicated_texts.append(text)
            deduplicated_scores.append(score)
    return deduplicated_texts, deduplicated_scores


def _predict(image: np.ndarray) -> tuple[list[str], list[float]]:
    if MODEL is None:
        raise RuntimeError("model not ready")
    return collect_recognition(MODEL.predict(input=image))


@app.post("/ocr")
async def ocr(file: UploadFile = File(...)) -> dict[str, list[Any]]:
    if MODEL_ERROR:
        raise HTTPException(status_code=503, detail="OCR model unavailable")
    if MODEL is None:
        raise HTTPException(status_code=503, detail="OCR model is starting")
    if file.content_type and not file.content_type.startswith("image/"):
        raise HTTPException(status_code=415, detail="image file required")
    content = await file.read(MAX_UPLOAD_BYTES + 1)
    if not content or len(content) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="invalid image size")
    image = cv2.imdecode(np.frombuffer(content, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise HTTPException(status_code=400, detail="invalid image")
    try:
        async with INFERENCE_LIMIT:
            texts, scores = await run_in_threadpool(_predict, image)
        return {"rec_texts": texts, "rec_scores": scores}
    except HTTPException:
        raise
    except Exception:
        LOGGER.exception("PaddleOCR inference failed")
        raise HTTPException(status_code=502, detail="OCR inference failed") from None
