import logging
import os
from logging.handlers import RotatingFileHandler

import uvicorn
from dotenv import load_dotenv

load_dotenv()

if __name__ == '__main__':
    os.makedirs(os.getenv('WILLIAMS_LOG_DIR', 'data'), exist_ok=True)
    log_path = os.path.join(
        os.getenv('WILLIAMS_LOG_DIR', 'data'),
        'williams.log',
    )
    handler = RotatingFileHandler(
        log_path,
        maxBytes=int(os.getenv('WILLIAMS_LOG_MAX_BYTES', str(10 * 1024 * 1024))),
        backupCount=max(1, int(os.getenv('WILLIAMS_LOG_BACKUP_COUNT', '5'))),
        encoding='utf-8',
    )
    logging.basicConfig(
        level=getattr(logging, os.getenv('LOG_LEVEL', 'INFO').upper(), logging.INFO),
        handlers=[handler, logging.StreamHandler()],
        format='%(asctime)s %(levelname)s %(name)s %(message)s',
    )
    uvicorn.run(
        'server:app',
        host=os.getenv('MOBILE_API_HOST', '127.0.0.1'),
        port=int(os.getenv('MOBILE_API_PORT', '8000')),
        reload=False,
    )
