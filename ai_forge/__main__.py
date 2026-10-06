from .main import app,settings
import uvicorn
if __name__=="__main__": uvicorn.run(app,host=settings.host,port=settings.port)
