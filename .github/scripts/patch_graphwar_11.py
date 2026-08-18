#!/usr/bin/env python3
import struct, sys, zipfile

TARGETS = {
    'Graphwar/GameData.class': {'addPlayer', 'addPC'},
    'Graphwar/PlayerBoard.class': {'<init>'},
    'GraphServer/GraphServer.class': {'handleMessage'},
}

def u1(b,o): return b[o], o+1
def u2(b,o): return struct.unpack_from('>H', b, o)[0], o+2
def u4(b,o): return struct.unpack_from('>I', b, o)[0], o+4

def patch_class(data, wanted):
    b = bytearray(data)
    o = 0
    magic,o = u4(b,o)
    if magic != 0xCAFEBABE: raise ValueError('not a class file')
    _,o=u2(b,o); _,o=u2(b,o); cp_count,o=u2(b,o)
    cp = [None]*cp_count
    i=1
    while i < cp_count:
        tag,o=u1(b,o)
        if tag==1:
            n,o=u2(b,o); cp[i]=('Utf8',bytes(b[o:o+n]).decode('utf-8','replace')); o+=n
        elif tag in (3,4): o+=4
        elif tag in (5,6): o+=8; i+=1
        elif tag in (7,8,16,19,20): o+=2
        elif tag in (9,10,11,12,17,18): o+=4
        elif tag==15: o+=3
        else: raise ValueError(f'unknown constant-pool tag {tag}')
        i+=1
    def utf(idx):
        e=cp[idx]
        return e[1] if e and e[0]=='Utf8' else None
    o += 6
    n,o=u2(b,o); o += 2*n
    nf,o=u2(b,o)
    for _ in range(nf):
        o += 6
        na,o=u2(b,o)
        for __ in range(na):
            _,o=u2(b,o); ln,o=u4(b,o); o += ln
    nm,o=u2(b,o)
    patched={name:0 for name in wanted}
    for _ in range(nm):
        _,o=u2(b,o); name_i,o=u2(b,o); _,o=u2(b,o); na,o=u2(b,o)
        name=utf(name_i)
        for __ in range(na):
            ai,o=u2(b,o); ln,o=u4(b,o); info=o
            if name in wanted and utf(ai)=='Code':
                q=info
                _,q=u2(b,q); _,q=u2(b,q); clen,q=u4(b,q)
                hits=[p for p in range(q,q+clen-1) if b[p]==0x10 and b[p+1]==0x0A]
                if len(hits)!=1:
                    raise ValueError(f'{name}: expected one bipush 10, found {len(hits)}')
                b[hits[0]+1]=0x0B
                patched[name]+=1
            o += ln
    bad={k:v for k,v in patched.items() if v!=1}
    if bad: raise ValueError(f'patch count wrong: {bad}')
    return bytes(b)

def main(src,dst):
    with zipfile.ZipFile(src,'r') as zin, zipfile.ZipFile(dst,'w') as zout:
        found=set()
        for info in zin.infolist():
            data=zin.read(info.filename)
            if info.filename in TARGETS:
                data=patch_class(data,TARGETS[info.filename]); found.add(info.filename)
                print('patched',info.filename,','.join(sorted(TARGETS[info.filename])))
            zout.writestr(info,data)
        missing=set(TARGETS)-found
        if missing: raise SystemExit(f'missing target classes: {missing}')

if __name__=='__main__':
    if len(sys.argv)!=3: raise SystemExit('usage: patch_graphwar_11.py input.jar output.jar')
    main(sys.argv[1],sys.argv[2])
