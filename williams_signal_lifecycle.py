"""Durable signal observations. This module has no exchange or order authority."""
from copy import deepcopy
import json
import re
from math import isfinite
from threading import RLock


def safe_reason(reason):
    return re.sub(r'(?i)(api[_-]?key|api[_-]?secret|signature|X-MBX-APIKEY)([\s=:]+)[^&\s]+',r'\1\2[REDACTED]',str(reason))[:1500]


def reason_category(reason):
    text=reason.upper()
    if text.startswith(('RUNTIME_OR_CAMPAIGN_NOT_READY','ADDITION PROFILE/SESSION/DAILY RISK')):return 'RUNTIME_GATE'
    for category,words in (
        ('ENTRY_TIME',('ENTRY_TIME','OUTSIDE_CONFIGURED_ENTRY_TIME')),
        ('HTF_CONTEXT',('H4','D1','HTF','DIRECTIONAL_CONTEXT')),
        ('RISK',('RISK','EQUITY','FUNDS','MARGIN','LOSS')),
        ('H1_STRUCTURE',('STRUCTUR','H1','FORMATION','CORRECTION')),
        ('EXCHANGE',('BINANCE','EXCHANGE','ORDER','RECONCIL')),
    ):
        if any(word in text for word in words):return category
    return 'RUNTIME_GATE'


def signal_lifecycle(signal,owner,now,snapshot_at,current):
    if owner:
        status=owner.get('order_status',owner.get('exchange_status','')).upper()
        if status in {'CANCELED','CANCELLED','EXPIRED','EXPIRED_IN_MATCH','REJECTED'}:
            return 'CANCELLED' if status.startswith('CANCEL') else status
        if status=='FILLED':return 'FILLED'
        if status=='PARTIALLY_FILLED':return 'PARTIAL_FILL'
        if status in {'NEW','ACCEPTED','WORKING'} and owner.get('campaign_status')=='ENTRY_PENDING':
            observed=max(owner.get('last_reconciled_ms',0),owner.get('created_at_ms',0))
            return 'BINANCE_PENDING' if 0<observed<=now and now-observed<=120_000 else 'PENDING_UNVERIFIED'
        if 'UNKNOWN' in status or status in {'TRIGGERED','PENDING'}:return 'PENDING_UNVERIFIED'
    known=signal.get('known_at_ms',0)
    if known<=0 or known>now:return 'DETECTED'
    if signal.get('expires_at_ms',signal.get('signal_bar_time_ms',0)+18_000_000)<=now:return 'EXPIRED'
    if not current:return 'INVALIDATED'
    if snapshot_at<=0 or now<snapshot_at or now-snapshot_at>120_000:return 'DETECTED'
    return 'VALID'


def signal_facts(signals,bars,frame_ms):
    facts=[]
    for s in signals:
        i=next((int(s.setup_context[k]) for k in ('formation_index','base_index','start_index','thumb_index') if k in s.setup_context),s.source_candle_index)
        formation=bars[i].t if 0<=i<len(bars) else s.signal_bar_time_ms
        def candle(bar):return [bar.t,bar.o,bar.h,bar.l,bar.c,bar.v] if bar is not None else None
        confirmation=next((b for b in bars if b.t==s.signal_bar_time_ms),None)
        facts.append(dict(formation_candle=candle(bars[i] if 0<=i<len(bars) else confirmation),
            confirmation_candle=candle(confirmation),teeth_at_detection=s.teeth_at_detection if isfinite(s.teeth_at_detection) else None,
            signal_id=s.signal_id,family=s.signal_type.value,type=s.signal_type.value,
            direction='LONG' if s.side=='BUY' else 'SHORT',role=s.role.value,
            signal_bar_time_ms=s.signal_bar_time_ms,known_at_ms=s.signal_bar_time_ms+frame_ms,
            formation_at_ms=formation,formation_time_basis='SOURCE_PIVOT_OR_BASE' if formation!=s.signal_bar_time_ms else 'SIGNAL_BAR',
            expires_at_ms=min(s.expires_at_ms,s.signal_bar_time_ms+5*frame_ms) if s.expires_at_ms>0 else s.signal_bar_time_ms+5*frame_ms,
            trigger=s.trigger_price,protective_stop=s.protective_reference,strategy_profile=s.strategy_profile,
            execution_semantics=s.execution_semantics,setup_context=dict(s.setup_context)))
    return facts


class SignalJournal:
    _lock=RLock()

    def __init__(self,db):self.db=db
    def _load(self,symbol):return json.loads(self.db.state_get('signal_timeline_'+symbol,'{"signals":[],"events":[]}'))
    def _save(self,symbol,data):self.db.state_set('signal_timeline_'+symbol,json.dumps(data,allow_nan=False))
    def _event(self,data,s,state,stage,reason,now,scope='SIGNAL'):
        reason=safe_reason(reason);key=f'{s["signal_id"]}:{stage}:{state}:{reason}'
        if any(e['event_id']==key for e in data['events']):return
        data['events']=(data['events']+[dict(deepcopy(s),event_id=key,state=state,stage=stage,reason=reason,
            reason_category=reason_category(reason),observed_at_ms=now,attribution=scope,can_place_orders=False)])[-256:]

    def observe(self,symbol,signals,now,policy):
        with self._lock:
            data=self._load(symbol);known={s['signal_id']:s for s in data['signals']};ids={s['signal_id'] for s in signals}
            for s in known.values():
                if s.get('last_seen_at_ms')==data.get('snapshot_at_ms') and s['signal_id'] not in ids:
                    self._event(data,s,'EXPIRED' if s['expires_at_ms']<=now else 'INVALIDATED','FORMATION','FORMATION_NO_LONGER_PRESENT_IN_CLOSED_BAR_SCAN',now)
            for source in signals:
                s=dict(deepcopy(source),first_seen_at_ms=known.get(source['signal_id'],{}).get('first_seen_at_ms',now),last_seen_at_ms=now)
                self._event(data,s,'DETECTED','FORMATION','FORMATION_OBSERVED',now)
                state=signal_lifecycle(s,None,now,now,True)
                self._event(data,s,state,'CONFIRMATION','CLOSED_BAR_FORMATION_CONFIRMED' if state=='VALID' else state,now)
                if not policy.allows(now):self._event(data,s,'BLOCKED','ENTRY_TIME','ENTRY_TIME_POLICY_BLOCKED: '+json.dumps(policy.to_dict(),sort_keys=True),now,'PAIR_GATE')
                known[s['signal_id']]=s
            data.update(signals=sorted(known.values(),key=lambda s:s['last_seen_at_ms'])[-100:],snapshot_at_ms=now)
            self._save(symbol,data)

    def decision(self,symbol,stage,reason,now,signal_id='',accepted=False):
        with self._lock:
            data=self._load(symbol)
            for s in data['signals']:
                if (s['signal_id']==signal_id if signal_id else s.get('last_seen_at_ms')==data.get('snapshot_at_ms')):
                    self._event(data,s,'ADMITTED' if accepted else 'BLOCKED',stage,reason,now,'SIGNAL' if signal_id else 'PAIR_GATE')
            self._save(symbol,data)

    def order(self,symbol,signal_id,order,now,reason=''):
        with self._lock:
            data=self._load(symbol)
            for s in data['signals']:
                if s['signal_id']!=signal_id:continue
                owner=dict(order_status=str(order.get('status','')),campaign_status='ENTRY_PENDING',last_reconciled_ms=now,
                    client_id=str(order.get('clientOrderId','')))
                s['exchange_observation']=owner
                self._event(data,s,signal_lifecycle(s,owner,now,data.get('snapshot_at_ms',0),True),'EXCHANGE',reason or owner['order_status'],now)
            self._save(symbol,data)

    def view(self,symbol,now):
        with self._lock:
            data=self._load(symbol)
            data['signals']=[dict(s,state=signal_lifecycle(s,s.get('exchange_observation'),now,data.get('snapshot_at_ms',0),s.get('last_seen_at_ms')==data.get('snapshot_at_ms')),can_place_orders=False) for s in data['signals']]
            data['retention_events_per_symbol']=256
            return data
