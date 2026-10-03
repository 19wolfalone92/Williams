import requests
class Telegram:
    def __init__(self,token,chat_id):self.token=token; self.chat_id=chat_id; self.base=f'https://api.telegram.org/bot{token}'
    def send(self,text):
        if not self.token or not self.chat_id:return
        r=requests.post(self.base+'/sendMessage',data={'chat_id':self.chat_id,'text':text},timeout=15); r.raise_for_status()
