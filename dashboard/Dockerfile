FROM node:26-alpine AS frontend
WORKDIR /build
COPY package.json ./
RUN npm install --no-audit --no-fund
COPY vite.config.js index.html ./
COPY src ./src
RUN npm run build

FROM python:3.11-slim AS runtime
WORKDIR /app
COPY backend/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY backend/main.py ./main.py
COPY --from=frontend /build/dist /app/static
ENV STATIC_DIR=/app/static
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
