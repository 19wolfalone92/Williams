import os, uvicorn
from dotenv import load_dotenv
load_dotenv()
if __name__=='__main__':uvicorn.run('server:app',host=os.getenv('MOBILE_API_HOST','0.0.0.0'),port=int(os.getenv('MOBILE_API_PORT','8000')),reload=False)
