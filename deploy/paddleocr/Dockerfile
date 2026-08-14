FROM python:3.10.20-slim-bookworm

ARG PADDLE_WHEEL_URL=https://files.pythonhosted.org/packages/50/27/5eb02041429f36d9f57e2fd81893a1f062618c60c8046c90c6d92d7b955f/paddlepaddle-3.3.1-cp310-cp310-manylinux1_x86_64.whl
ARG PADDLE_WHEEL_SHA256=6747e81b4ab46c9caaae248f7b9999ccd4d5bb3ca9083173b0ce4c14e4a68db5
ARG PADDLE_WHEEL_NAME=paddlepaddle-3.3.1-cp310-cp310-manylinux1_x86_64.whl
ARG UV_WHEEL_URL=https://files.pythonhosted.org/packages/0d/28/3fa1c2061d588184840e3e4ab17e6d318c744bb2fcb15ddb0c29b5bc0bb3/uv-0.11.29-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl
ARG UV_WHEEL_SHA256=eec03a8b63d55915694db3af4e91324b39ced49e2aeac7af37851c7eb3f470ea
ARG UV_WHEEL_NAME=uv-0.11.29-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl
ARG LIBGOMP_URL=https://deb.debian.org/debian/pool/main/g/gcc-12/libgomp1_12.2.0-14%2Bdeb12u1_amd64.deb
ARG LIBGOMP_SHA256=48fec46bda7f5b1638b9e959889bfbc20491247d402d120bb152687eb48143d7
ARG LIBGOMP_NAME=libgomp1_12.2.0-14+deb12u1_amd64.deb

ENV PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    UV_HTTP_TIMEOUT=120 \
    UV_HTTP_RETRIES=10 \
    CUDA_VISIBLE_DEVICES="" \
    FLAGS_use_mkldnn=0 \
    OMP_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1 \
    MKL_NUM_THREADS=1 \
    NUMEXPR_NUM_THREADS=1 \
    PADDLE_HOME=/data/paddle \
    PADDLE_PDX_CACHE_HOME=/data/paddlex \
    XDG_CACHE_HOME=/data/cache \
    OCR_MAX_UPLOAD_BYTES=20971520

COPY download_wheel.py /usr/local/bin/download_wheel.py

RUN --mount=type=cache,target=/root/.cache/pip \
    --mount=type=cache,target=/root/.cache/uv \
    python /usr/local/bin/download_wheel.py \
        --url "${LIBGOMP_URL}" \
        --output "/root/.cache/pip/wheels/${LIBGOMP_NAME}" \
        --sha256 "${LIBGOMP_SHA256}" \
        --workers 4 \
    && dpkg -i "/root/.cache/pip/wheels/${LIBGOMP_NAME}" \
    && python /usr/local/bin/download_wheel.py \
        --url "${PADDLE_WHEEL_URL}" \
        --output "/root/.cache/pip/wheels/${PADDLE_WHEEL_NAME}" \
        --sha256 "${PADDLE_WHEEL_SHA256}" \
        --workers 8 \
    && python /usr/local/bin/download_wheel.py \
        --url "${UV_WHEEL_URL}" \
        --output "/root/.cache/pip/wheels/${UV_WHEEL_NAME}" \
        --sha256 "${UV_WHEEL_SHA256}" \
        --workers 8 \
    && python -m pip install --no-deps "/root/.cache/pip/wheels/${UV_WHEEL_NAME}" \
    && uv pip install --system "/root/.cache/pip/wheels/${PADDLE_WHEEL_NAME}" \
    && python -c "import paddle; assert paddle.device.is_compiled_with_cuda() is False"

ARG OPENCV_CONTRIB_HEADLESS_URL=https://files.pythonhosted.org/packages/00/fc/b01f878cef02f619a4686683db31451d0e3e961646e65ec09ea802c8ceda/opencv_contrib_python_headless-4.10.0.84-cp37-abi3-manylinux_2_17_x86_64.manylinux2014_x86_64.whl
ARG OPENCV_CONTRIB_HEADLESS_SHA256=89c16eb5f888aee7bf664106e12c423705d29d1b094876b66aa4e33d4e8ec905
ARG OPENCV_CONTRIB_HEADLESS_NAME=opencv_contrib_python_headless-4.10.0.84-cp37-abi3-manylinux_2_17_x86_64.manylinux2014_x86_64.whl

COPY build_opencv_contrib_shim.py /usr/local/bin/build_opencv_contrib_shim.py

RUN --mount=type=cache,target=/root/.cache/pip \
    --mount=type=cache,target=/root/.cache/uv \
    python /usr/local/bin/download_wheel.py \
        --url "${OPENCV_CONTRIB_HEADLESS_URL}" \
        --output "/root/.cache/pip/wheels/${OPENCV_CONTRIB_HEADLESS_NAME}" \
        --sha256 "${OPENCV_CONTRIB_HEADLESS_SHA256}" \
        --workers 8 \
    && python /usr/local/bin/build_opencv_contrib_shim.py /tmp/opencv-contrib-shim \
    && uv pip install --system \
        "/root/.cache/pip/wheels/${OPENCV_CONTRIB_HEADLESS_NAME}" \
        /tmp/opencv-contrib-shim/opencv_contrib_python-4.10.0.84-py3-none-any.whl \
    && python -c "import cv2; assert cv2.__version__ == '4.10.0'"

RUN --mount=type=cache,target=/root/.cache/uv \
    uv pip install --system \
        paddleocr==3.7.0 \
        fastapi==0.141.1 \
        uvicorn==0.52.3 \
        python-multipart==0.0.32 \
    && python -c "import paddle, paddleocr; assert paddle.device.is_compiled_with_cuda() is False"

WORKDIR /app
COPY app.py /app/app.py

RUN mkdir -p /data/paddle /data/paddlex /data/cache \
    && python -c "from app import collect_recognition; assert collect_recognition({'rec_texts':['ok'],'rec_scores':[0.9]}) == (['ok'], [0.9])"

EXPOSE 8868

HEALTHCHECK --interval=15s --timeout=5s --start-period=900s --retries=40 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8868/health', timeout=3).read()" || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8868", "--workers", "1"]
