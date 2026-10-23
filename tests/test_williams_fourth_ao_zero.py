"""Course PDF44: a zero touch is not the required cross past zero."""
import math
import pytest
from williams_wave_hierarchy import classify_fourth_ao


@pytest.mark.parametrize("third,minimum,bars,expected",[
    (10.,0.,120,"NEAR_ZERO_UNCONFIRMED"),
    (10.,-0.001,120,"MINIMUM_ONLY"),
    (10.,.5,120,"NEAR_ZERO_UNCONFIRMED"),
    (10.,1.,120,"NEAR_ZERO_UNCONFIRMED"),
    (10.,1.01,120,"NO_ZERO_CROSS"),
    (10.,-1.,30,"WRONG_SCALE"),
    (10.,-1.,141,"WRONG_SCALE"),
    (math.nan,0.,120,"MISSING_W3_MOMENTUM"),
    (0.,0.,120,"MISSING_W3_MOMENTUM"),
    (10.,math.nan,120,"MISSING_W3_MOMENTUM"),
])
def test_same_causal_classification_as_android(third,minimum,bars,expected):
    assert classify_fourth_ao(third,minimum,bars)==expected
