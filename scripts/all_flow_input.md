conda activate live_photo_agent
export DASHSCOPE_API_KEY='sk-ws-H.EDDIMHE.XPJN.MEUCIFj-bi6j3oX3ba8uOeEP9ObU9oPqmb5VwpO6WgANKkm3AiEAvDesS6EN6P51MCfDeL3skveLQT2lgk7ls181Xvia8vk'

export DASHSCOPE_WORKSPACE_ID='ws-a6giruup05d0ztyb'

source scripts/use_endpoint_backend.sh \
  "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions" \
  "$DASHSCOPE_API_KEY" \
  qwen-omni-turbo



uvicorn live_photo_agent.api:app --host 127.0.0.1 --port 8000