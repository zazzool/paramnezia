# Generates docs/icon.svg variants ("Забвение" is B.svg); the Android drawables are converted from B.
import math, random
random.seed(7)
HEAD0="M40,120 L40,88 C40,80 35,76 33.5,68 C30,52 33,30 54,25.5 C70,22.5 80,33 80,46 C80,49 81,51 83.5,55.5 C84.5,57.5 83.5,58.5 80.5,59 L80.5,62 C81,63 80,64 79.2,64.5 C80,65.5 79.5,67 79,68.5 C78.5,72 75,73.5 70,73 L68.5,88 L68.5,120 Z"
import re as _re
def _tf(d,k=0.85,tx=4.5,ty=9.3):
    out=[];nums=_re.findall(r'[MCLZ]|-?[\d.]+',d);xy=0
    for t in nums:
        if t in 'MCLZ': out.append(t);xy=0;continue
        v=float(t); v=v*k+(tx if xy%2==0 else ty); xy+=1; out.append(f"{v:.2f}")
    return ' '.join(out)
HEAD=_tf(HEAD0)
def bez(p0,p1,p2,p3,n=24):
    return [tuple((1-t)**3*p0[i]+3*(1-t)**2*t*p1[i]+3*(1-t)*t*t*p2[i]+t**3*p3[i] for i in (0,1)) for t in [k/n for k in range(1,n+1)]]
def poly(d):
    import re
    toks=re.findall(r'[MCLZ]|-?[\d.]+',d); pts=[];cur=None;i=0;cmd=None
    while i<len(toks):
        t=toks[i]
        if t in 'MCLZ': cmd=t;i+=1;continue
        if cmd=='M': cur=(float(toks[i]),float(toks[i+1]));pts.append(cur);i+=2
        elif cmd=='L': cur=(float(toks[i]),float(toks[i+1]));pts.append(cur);i+=2
        elif cmd=='C':
            a=[float(x) for x in toks[i:i+6]];p1,p2,p3=(a[0],a[1]),(a[2],a[3]),(a[4],a[5]);pts+=bez(cur,p1,p2,p3);cur=p3;i+=6
    return pts
P=poly(HEAD)
def inside(x,y):
    c=False
    for (x1,y1),(x2,y2) in zip(P,P[1:]+P[:1]):
        if (y1>y)!=(y2>y) and x<(x2-x1)*(y-y1)/(y2-y1)+x1: c=not c
    return c
def svg(defs,body): return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108"><defs>{defs}</defs>{body}</svg>'
NIGHT='<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#241A5C"/><stop offset="1" stop-color="#07081A"/></linearGradient>'
# 1 Дежавю: голова и её цветное эхо
A=svg(NIGHT.replace('id="bg"','id="bgA"')+'<linearGradient id="wA" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#E4E0FF"/></linearGradient>',
 f'<rect width="108" height="108" fill="url(#bgA)"/>'
 f'<g transform="translate(-6,-2.5)"><path d="{HEAD}" fill="#00E5FF"/></g>'
 f'<g transform="translate(-3,-1.2)"><path d="{HEAD}" fill="#FF3DA6"/></g>'
 f'<path d="{HEAD}" fill="url(#wA)"/>')
# 2 Забвение: затылок рассыпается на пиксели
S=3.6; G=0.55; cells=''; stair=[]
import math as _m
for gy in range(0,32):
    y=24+gy*S
    edge=50+3.2*_m.sin(gy*0.9)+2.2*_m.sin(gy*2.3+1)
    edge=24+round((edge-24)/S)*S
    stair.append((edge,y))
    for gx in range(0,20):
        x=24+gx*S; cx,cy=x+S/2,y+S/2
        if x>=edge-0.01 or not inside(cx,cy): continue
        f=min(1,(edge-x)/20)
        if random.random()<f*0.8: continue
        dx=-random.random()*f*f*12; dy=(random.random()-0.5)*f*5
        op=max(0.3,1-f*0.7)
        col='#FFFFFF' if f<0.3 else random.choice(['#00E5FF','#FF4FB0','#C4B5FD','#FFFFFF'])
        cells+=f'<rect x="{x+dx:.2f}" y="{y+dy:.2f}" width="{S-G:.2f}" height="{S-G:.2f}" rx="0.5" fill="{col}" fill-opacity="{op:.2f}"/>'
pts=[(110,-2)]+[(110,24)]
pts=[(110,0),(stair[0][0],0)]
for e,y in stair: pts+=[(e,y),(e,y+S)]
pts+=[(stair[-1][0],110),(110,110)]
clip='M'+' L'.join(f'{x:.2f},{y:.2f}' for x,y in pts)+' Z'
B=svg('<linearGradient id="bgB" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7C3AED"/><stop offset="0.6" stop-color="#4338CA"/><stop offset="1" stop-color="#0EA5E9"/></linearGradient>'+f'<clipPath id="faceB"><path d="{clip}"/></clipPath>',
 f'<rect width="108" height="108" fill="url(#bgB)"/><g clip-path="url(#faceB)"><path d="{HEAD}" fill="#FFFFFF"/></g>{cells}')
# 3 Сбой: сфера, разрезанная на полосы со смещением
bands=[(30,40,0),(40,49,-6),(49,55,7),(55,66,-3),(66,78,0)]
out=''
for i,(y0,y1,dx) in enumerate(bands):
    out+=f'<clipPath id="cb{i}"><rect x="0" y="{y0}" width="108" height="{y1-y0}"/></clipPath>'
body='<rect width="108" height="108" fill="url(#bgC)"/>'
orb=lambda cx,cy,r,fill: f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}"/>'
for i,(y0,y1,dx) in enumerate(bands):
    g=''
    if dx: g+=orb(54+dx-2.5,54,24,'#00E5FF')+orb(54+dx+2.5,54,24,'#FF3DA6')
    g+=orb(54+dx,54,24,'url(#orbC)')
    body+=f'<g clip-path="url(#cb{i})">{g}</g>'
C=svg(NIGHT.replace('id="bg"','id="bgC"')+out+'<radialGradient id="orbC" cx="0.38" cy="0.32" r="0.75"><stop offset="0" stop-color="#FFFFFF"/><stop offset="0.45" stop-color="#C9B8FF"/><stop offset="1" stop-color="#7C4DFF"/></radialGradient>',body)
for n,s in (('A',A),('B',B),('C',C)): open(f'{n}.svg','w').write(s)
names={'A':'1. Дежавю','B':'2. Забвение','C':'3. Сбой'}
def sized(s,w): return s.replace('width="108" height="108"',f'width="{w}" height="{w}"',1)
cells=''
for n in 'ABC':
    s=open(f'{n}.svg').read()
    cells+=f'<div class="col"><div class="mask c">{sized(s,220)}</div><div class="row"><div class="mask s">{sized(s,64)}</div><div class="mask c">{sized(s,64)}</div><div class="mask s">{sized(s,40)}</div></div><div class="lbl">{names[n]}</div></div>'
open('preview.html','w').write(f'''<html><body style="margin:0;width:900px;background:linear-gradient(135deg,#efeaff,#d6ecff);font-family:sans-serif"><div style="display:flex;gap:56px;padding:40px">{cells}</div>
<style>.col{{display:flex;flex-direction:column;align-items:center;gap:18px}} .mask{{overflow:hidden;line-height:0;box-shadow:0 6px 18px rgba(0,0,0,.25)}}
.mask svg{{transform:scale(1.5)}} .c{{border-radius:50%}} .s{{border-radius:28%}} .row{{display:flex;gap:14px;align-items:center}} .lbl{{font-size:22px;font-weight:700;color:#1b1640}}</style></body></html>''')
