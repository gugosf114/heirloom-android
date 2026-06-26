# Heirloom restoration pipeline — Cloud Run (L4, scale-to-zero).
# CUDA 11.8 / Python 3.10 to match the proven cog-adaface build.
FROM nvidia/cuda:11.8.0-cudnn8-runtime-ubuntu22.04

ENV DEBIAN_FRONTEND=noninteractive PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1

RUN apt-get update && apt-get install -y --no-install-recommends \
      python3.10 python3-pip python3.10-distutils libgl1 libglib2.0-0 ca-certificates wget \
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

WORKDIR /srv
COPY app /srv/app

ENV ADAFACE_WEIGHTS_DIR=/opt/adaface_weights
ENV PORT=8080
CMD ["sh", "-c", "python -m uvicorn app.server:app --host 0.0.0.0 --port ${PORT}"]
