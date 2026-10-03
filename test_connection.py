import os
from dotenv import load_dotenv
from binance_client import BinanceSpotClient

def main():
    load_dotenv()
    c=BinanceSpotClient(os.getenv('BINANCE_API_KEY',''),os.getenv('BINANCE_API_SECRET',''),testnet=os.getenv('TESTNET','true').lower()=='true')
    c.sync_time();print('PING:',c.ping());print('TESTNET:',c.testnet);print('SYMBOL:',os.getenv('SYMBOL','BTCUSDT'));print('TICKER:',c.ticker_price(os.getenv('SYMBOL','BTCUSDT')))
    a=c.account();print('ACCOUNT:',{'canTrade':a.get('canTrade'),'balances':[b for b in a.get('balances',[]) if float(b['free']) or float(b['locked'])]})

if __name__=='__main__':main()
