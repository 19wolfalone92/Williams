FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
COPY . /app/ai_forge
RUN useradd --create-home --uid 10001 forge && chown -R forge:forge /app
USER forge
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD python -c "import urllib.request; urllib.request.urlopen(\"http://127.0.0.1:8787/health\", timeout=3)"
CMD ["python","-m","ai_forge"]
