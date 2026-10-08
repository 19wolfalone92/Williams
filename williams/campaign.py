"""Pure campaign sequencing: actual first entry defines Step 1."""
from __future__ import annotations
from dataclasses import dataclass
WEIGHTS=(1,5,4,3,2)
@dataclass(frozen=True)
class CampaignStep:
    step:int; signal_type:str; role:str; weight:int

def step_for_first_signal(signal_type:str)->CampaignStep:
    return CampaignStep(1,str(signal_type).upper(),"INITIAL",WEIGHTS[0])
def next_step(current_step:int,signal_type:str)->CampaignStep:
    step=int(current_step)+1
    if step>len(WEIGHTS): raise ValueError("campaign has reached five tranches")
    return CampaignStep(step,str(signal_type).upper(),"ADD_ON",WEIGHTS[step-1])
def can_add(current_step:int,signal_type:str,used_types:list[str])->bool:
    if int(current_step)>=5:return False
    st=str(signal_type).upper()
    if st=="REVERSAL":return False
    if st=="SUPER_AO" and "SUPER_AO" in used_types:return False
    return st!="" and st not in {""}
