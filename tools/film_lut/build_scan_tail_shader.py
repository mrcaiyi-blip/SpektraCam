#!/usr/bin/env python3
"""Rebuild the checked-in local Vulkan shader (Python stdlib + NDK glslc)."""
from pathlib import Path
import hashlib,struct,subprocess,sys,tempfile,json
root=Path(__file__).resolve().parents[2]
p=root/'third_party/spektrafilm_android/patches'
compiler=sys.argv[1]
with tempfile.TemporaryDirectory() as temp:
    binary=Path(temp)/'tail.spv'
    subprocess.run([compiler,'--target-env=vulkan1.0','-O',str(p/'scan_tail.comp'),'-o',str(binary)],check=True)
    data=binary.read_bytes();words=struct.unpack('<'+'I'*(len(data)//4),data)
header='// Generated from scan_tail.comp by build_scan_tail_shader.py; GPL-3.0-only.\n#pragma once\n#include <cstdint>\nstatic const uint32_t kLutcamScanTailSpv[]={\n'
header+=''.join('    '+','.join(f'0x{w:08x}' for w in words[i:i+8])+',\n' for i in range(0,len(words),8))+'};\n'
(p/'scan_tail_spv.h').write_text(header)
(p/'shader_sha256.json').write_text(json.dumps({'glsl':hashlib.sha256((p/'scan_tail.comp').read_bytes()).hexdigest(),'spirv':hashlib.sha256(data).hexdigest()},indent=2)+'\n')
print('Built scan tail shader:',len(data),'bytes')
