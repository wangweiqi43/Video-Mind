FROM python:3.10.20-slim-bookworm

ARG TORCH_WHEEL_URL=https://download-r2.pytorch.org/whl/cpu/torch-2.11.0%2Bcpu-cp310-cp310-manylinux_2_28_x86_64.whl
ARG TORCH_WHEEL_SHA256=f378df648bf7fb94bbc820dfec37d3d346bd1703c692a2215edebf6edcef8b75
ARG TORCH_WHEEL_NAME=torch-2.11.0+cpu-cp310-cp310-manylinux_2_28_x86_64.whl
ARG TORCHVISION_WHEEL_URL=https://download-r2.pytorch.org/whl/cpu/torchvision-0.26.0%2Bcpu-cp310-cp310-manylinux_2_28_x86_64.whl
ARG TORCHVISION_WHEEL_SHA256=967048f44f5bfcd05afed1c4b595cb30b4419fa0e08c296be403c88e82396c30
ARG TORCHVISION_WHEEL_NAME=torchvision-0.26.0+cpu-cp310-cp310-manylinux_2_28_x86_64.whl
ARG UV_WHEEL_URL=https://files.pythonhosted.org/packages/0d/28/3fa1c2061d588184840e3e4ab17e6d318c744bb2fcb15ddb0c29b5bc0bb3/uv-0.11.29-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl
ARG UV_WHEEL_SHA256=eec03a8b63d55915694db3af4e91324b39ced49e2aeac7af37851c7eb3f470ea
ARG UV_WHEEL_NAME=uv-0.11.29-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl

ENV PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    UV_HTTP_TIMEOUT=120 \
    UV_HTTP_RETRIES=10 \
    CUDA_VISIBLE_DEVICES="" \
    TORCH_DEVICE_BACKEND_AUTOLOAD=0 \
    MINERU_DEVICE_MODE=cpu \
    MINERU_MODEL_SOURCE=modelscope \
    MINERU_API_MAX_CONCURRENT_REQUESTS=1 \
    MINERU_PROCESSING_WINDOW_SIZE=1 \
    MINERU_INTRA_OP_NUM_THREADS=1 \
    MINERU_INTER_OP_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1 \
    OMP_NUM_THREADS=1 \
    MKL_NUM_THREADS=1 \
    MINERU_API_OUTPUT_ROOT=/data/output \
    MODELSCOPE_HOME=/data/modelscope \
    MODELSCOPE_CACHE=/data/modelscope/cache \
    HF_HOME=/data/huggingface \
    XDG_CACHE_HOME=/data/cache

COPY download_wheel.py /usr/local/bin/download_wheel.py

RUN --mount=type=cache,target=/root/.cache/pip \
    --mount=type=cache,target=/root/.cache/uv \
    python /usr/local/bin/download_wheel.py \
        --url "${TORCH_WHEEL_URL}" \
        --output "/root/.cache/pip/wheels/${TORCH_WHEEL_NAME}" \
        --sha256 "${TORCH_WHEEL_SHA256}" \
        --workers 8 \
    && python /usr/local/bin/download_wheel.py \
        --url "${TORCHVISION_WHEEL_URL}" \
        --output "/root/.cache/pip/wheels/${TORCHVISION_WHEEL_NAME}" \
        --sha256 "${TORCHVISION_WHEEL_SHA256}" \
        --workers 8 \
    && python /usr/local/bin/download_wheel.py \
        --url "${UV_WHEEL_URL}" \
        --output "/root/.cache/pip/wheels/${UV_WHEEL_NAME}" \
        --sha256 "${UV_WHEEL_SHA256}" \
        --workers 8 \
    && python -m pip install --no-deps "/root/.cache/pip/wheels/${UV_WHEEL_NAME}" \
    && uv pip install --system \
        --index-url https://pypi.org/simple \
        "/root/.cache/pip/wheels/${TORCH_WHEEL_NAME}" \
        "/root/.cache/pip/wheels/${TORCHVISION_WHEEL_NAME}" \
    && python -c "import torch, torchvision; assert torch.cuda.is_available() is False"

ARG OPENCV_HEADLESS_WHEEL_URL=https://files.pythonhosted.org/packages/89/53/e19c21e0c4eb1275c3e2c97b081103b6dfb3938172264d283a519bf728b9/opencv_python_headless-4.12.0.88-cp37-abi3-manylinux2014_x86_64.manylinux_2_17_x86_64.whl
ARG OPENCV_HEADLESS_WHEEL_SHA256=236c8df54a90f4d02076e6f9c1cc763d794542e886c576a6fee46ec8ff75a7a9
ARG OPENCV_HEADLESS_WHEEL_NAME=opencv_python_headless-4.12.0.88-cp37-abi3-manylinux2014_x86_64.manylinux_2_17_x86_64.whl

COPY build_opencv_shim.py /usr/local/bin/build_opencv_shim.py

RUN --mount=type=cache,target=/root/.cache/pip \
    --mount=type=cache,target=/root/.cache/uv \
    python /usr/local/bin/download_wheel.py \
        --url "${OPENCV_HEADLESS_WHEEL_URL}" \
        --output "/root/.cache/pip/wheels/${OPENCV_HEADLESS_WHEEL_NAME}" \
        --sha256 "${OPENCV_HEADLESS_WHEEL_SHA256}" \
        --workers 8 \
    && python /usr/local/bin/build_opencv_shim.py /tmp/opencv-shim \
    && uv pip install --system \
        "/root/.cache/pip/wheels/${OPENCV_HEADLESS_WHEEL_NAME}" \
        /tmp/opencv-shim/opencv_python-4.12.0.88-py3-none-any.whl \
    && python -c "import cv2; assert cv2.__version__ == '4.12.0'"

RUN --mount=type=cache,target=/root/.cache/uv \
    uv pip install --system "mineru[core]==3.4.4" \
    && python -c "import cv2, mineru, onnxruntime, torch; assert torch.cuda.is_available() is False"

RUN mkdir -p /data/output /data/modelscope /data/huggingface /data/cache

EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=20 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3).read()" || exit 1

CMD ["mineru-api", "--host", "0.0.0.0", "--port", "8000"]
