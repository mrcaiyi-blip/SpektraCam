#!/usr/bin/env python3
"""Build the local grain FIR shader (Python stdlib and Android NDK glslc)."""
from pathlib import Path
import hashlib,struct,subprocess,sys,tempfile,json
root=Path(__file__).resolve().parents[2];p=root/'third_party/spektrafilm_android/patches'
with tempfile.TemporaryDirectory() as t:
    binary=Path(t)/'grain.spv'
    subprocess.run([sys.argv[1],'--target-env=vulkan1.0','-O',str(p/'grain_tail.comp'),'-o',str(binary)],check=True)
    data=binary.read_bytes();words=struct.unpack('<'+'I'*(len(data)//4),data)
header='// Generated from grain_tail.comp by build_grain_tail_shader.py; GPL-3.0-only.\n#pragma once\n#include <cstdint>\nstatic const uint32_t kLutcamGrainTailSpv[]={\n'
header+=''.join('    '+','.join(f'0x{w:08x}' for w in words[i:i+8])+',\n' for i in range(0,len(words),8))+'};\n'
(p/'grain_tail_spv.h').write_text(header)
(p/'grain_shader_sha256.json').write_text(json.dumps({'glsl':hashlib.sha256((p/'grain_tail.comp').read_bytes()).hexdigest(),'spirv':hashlib.sha256(data).hexdigest()},indent=2)+'\n')
print('Built grain tail shader:',len(data),'bytes')
