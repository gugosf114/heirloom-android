# Heirloom restoration pipeline — Cloud Run (L4, scale-to-zero).
# CUDA 11.8 / Python 3.10 to match the proven cog-adaface build.
FROM nvidia/cuda:11.8.0-cudnn8-devel-ubuntu22.04

ENV DEBIAN_FRONTEND=noninteractive PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1

RUN apt-get update && apt-get install -y --no-install-recommends \
      python3.10 python3-pip python3.10-distutils python3.10-venv git libgl1 libglib2.0-0 ca-certificates wget \
      build-essential cmake unzip bzip2 pkg-config libopenblas-dev liblapack-dev libx11-dev \
 && rm -rf /var/lib/apt/lists/*
RUN ln -sf /usr/bin/python3.10 /usr/bin/python && python -m pip install --upgrade pip

# Torch first, from the cu118 wheel index (matches cog-adaface).
RUN pip install torch==2.0.1 torchvision==0.15.2 --index-url https://download.pytorch.org/whl/cu118

COPY requirements.txt /tmp/requirements.txt
RUN pip install -r /tmp/requirements.txt

# Bake AdaFace (HF repo) + MTCNN weights into the image so the container is
# self-contained — no runtime weight egress. Mirrors cog-adaface/cog.yaml.
RUN python -c "import os; from huggingface_hub import hf_hub_download as h; D='/opt/adaface_weights'; R='minchul/cvlface_adaface_ir101_webface4m'; os.makedirs(D, exist_ok=True); h(R,'files.txt',local_dir=D,local_dir_use_symlinks=False); F=sorted(set(open(D+'/files.txt').read().split()) | {'config.json','wrapper.py','model.safetensors'}); print('AdaFace: pulling',len(F),'files'); [h(R,x,local_dir=D,local_dir_use_symlinks=False) for x in F]; print('AdaFace download complete')"
RUN python -c "from facenet_pytorch import MTCNN; MTCNN(image_size=112, margin=0, post_process=False, select_largest=True, keep_all=False); print('MTCNN cached')"

# Real-ESRGAN x2 weights.
RUN mkdir -p /opt/weights && \
    wget -q -O /opt/weights/RealESRGAN_x2plus.pth \
      https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.1/RealESRGAN_x2plus.pth
ENV ESRGAN_WEIGHTS=/opt/weights/RealESRGAN_x2plus.pth

# --- Vendored models, each in its own venv (isolates old/conflicting deps) ---
# NOTE: weight URLs + repo entrypoints are best-effort from the upstream docs;
# verify on the first Cloud Build and adjust.

# CodeFormer (ships its own basicsr fork — must stay isolated).
RUN git clone --depth 1 https://github.com/sczhou/CodeFormer /opt/CodeFormer && \
    python -m venv /opt/CodeFormer/venv && \
    /opt/CodeFormer/venv/bin/pip install --no-cache-dir -U pip wheel packaging && \
    /opt/CodeFormer/venv/bin/pip install --no-cache-dir torch==2.0.1 torchvision==0.15.2 --index-url https://download.pytorch.org/whl/cu118 && \
    /opt/CodeFormer/venv/bin/pip install --no-cache-dir "numpy<2" && \
    /opt/CodeFormer/venv/bin/pip install --no-cache-dir -r /opt/CodeFormer/requirements.txt && \
    cd /opt/CodeFormer && /opt/CodeFormer/venv/bin/python basicsr/setup.py develop && \
    /opt/CodeFormer/venv/bin/python scripts/download_pretrained_models.py facelib && \
    /opt/CodeFormer/venv/bin/python scripts/download_pretrained_models.py CodeFormer

# DDColor (own venv).
RUN git clone --depth 1 https://github.com/piddnad/DDColor /opt/DDColor && \
    python -m venv /opt/DDColor/venv && \
    /opt/DDColor/venv/bin/pip install --no-cache-dir -U pip wheel packaging && \
    /opt/DDColor/venv/bin/pip install --no-cache-dir torch==2.0.1 torchvision==0.15.2 --index-url https://download.pytorch.org/whl/cu118 && \
    /opt/DDColor/venv/bin/pip install --no-cache-dir "numpy<2" && \
    /opt/DDColor/venv/bin/pip install --no-cache-dir -r /opt/DDColor/requirements.txt && \
    wget -q -O /opt/weights/ddcolor_modelscope.pth \
      https://huggingface.co/piddnad/DDColor-models/resolve/main/ddcolor_modelscope.pth

# BOPB — Microsoft Bringing-Old-Photos-Back-to-Life (own venv; dlib + sync-batchnorm,
# old torch). Canonical setup from upstream README; torch pin + checkpoint URLs are
# the most likely first-build failure points — verify and adjust.
RUN git clone --depth 1 https://github.com/microsoft/Bringing-Old-Photos-Back-to-Life.git /opt/BOPB && \
    cd /opt/BOPB/Face_Enhancement/models/networks && \
      git clone --depth 1 https://github.com/vacancy/Synchronized-BatchNorm-PyTorch && \
      cp -rf Synchronized-BatchNorm-PyTorch/sync_batchnorm . && \
    cd /opt/BOPB/Global/detection_models && \
      git clone --depth 1 https://github.com/vacancy/Synchronized-BatchNorm-PyTorch && \
      cp -rf Synchronized-BatchNorm-PyTorch/sync_batchnorm . && \
    cd /opt/BOPB/Face_Detection && \
      wget -q http://dlib.net/files/shape_predictor_68_face_landmarks.dat.bz2 && \
      bzip2 -d shape_predictor_68_face_landmarks.dat.bz2 && \
    cd /opt/BOPB/Face_Enhancement && \
      wget -q https://github.com/microsoft/Bringing-Old-Photos-Back-to-Life/releases/download/v1.0/face_checkpoints.zip && \
      unzip -q face_checkpoints.zip && \
    cd /opt/BOPB/Global && \
      wget -q https://github.com/microsoft/Bringing-Old-Photos-Back-to-Life/releases/download/v1.0/global_checkpoints.zip && \
      unzip -q global_checkpoints.zip && \
    python -m venv /opt/BOPB/venv && \
    /opt/BOPB/venv/bin/pip install --no-cache-dir -U pip wheel packaging && \
    /opt/BOPB/venv/bin/pip install --no-cache-dir torch==1.12.1+cu113 torchvision==0.13.1+cu113 --index-url https://download.pytorch.org/whl/cu113 && \
    /opt/BOPB/venv/bin/pip install --no-cache-dir "numpy<2" && \
    git clone --depth 1 https://github.com/davisking/dlib /opt/dlib && \
    cd /opt/dlib && /opt/BOPB/venv/bin/python setup.py install --no DLIB_USE_CUDA --set DLIB_NO_GUI_SUPPORT=YES && \
    /opt/BOPB/venv/bin/pip install --no-cache-dir -r /opt/BOPB/requirements.txt

WORKDIR /srv
COPY app /srv/app

ENV ADAFACE_WEIGHTS_DIR=/opt/adaface_weights
ENV PORT=8080
CMD ["sh", "-c", "python -m uvicorn app.server:app --host 0.0.0.0 --port ${PORT}"]
