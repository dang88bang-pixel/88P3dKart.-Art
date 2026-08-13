FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

ENV AGENT_DB_PATH=/data/agent.db
ENV AGENT_LOG_DIR=/var/log/agent
ENV AGENT_LOOP_HZ=20

VOLUME ["/data", "/var/log/agent"]

EXPOSE 8080

CMD ["uvicorn", "agent:app", "--host", "0.0.0.0", "--port", "8080"]
