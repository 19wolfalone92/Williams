from pathlib import Path
import matplotlib.pyplot as plt
def plot_backtest(df,equity,trades,out='backtest.png',title='Williams strategy backtest'):
 fig,ax=plt.subplots(figsize=(15,7));ax.plot(df.index,df['close'],label='Close');
 for c in ('lips_shifted','teeth_shifted','jaw_shifted'):
  if c in df:ax.plot(df.index,df[c],label=c)
 if not trades.empty:
  longs=trades[trades.side=='LONG'];ax.scatter(longs.entry_time,longs.entry_price,marker='^',label='Entry');ax.scatter(longs.exit_time,longs.exit_price,marker='v',label='Exit')
 ax.set_title(title);ax.legend();ax.grid(alpha=.25);Path(out).parent.mkdir(parents=True,exist_ok=True);fig.tight_layout();fig.savefig(out,dpi=160);plt.close(fig);return out
