"""Run with the original Workbench's Python environment; no private images.

Usage: PYTHONPATH=/path/to/spektrafilm/src python generate_metering_goldens.py
"""
from pathlib import Path
import numpy as np
from spektrafilm.utils.autoexposure import measure_autoexposure_ev

methods = ('histogram_middle_gray', 'center_weighted', 'matrix', 'multi_zone',
           'partial', 'highlight_weighted', 'median', 'average')
cases = [(17, 13, 0), (17, 13, 1), (64, 48, 2), (63, 47, 3), (777, 513, 2)]
lines = ['// Generated from the Python Workbench, linear ProPhoto input, float32 pixels.',
         '// Reproduce using generate_metering_goldens.py in its Python environment.',
         'static const char* methods[] = {' + ','.join(f'"{m}"' for m in methods) + '};',
         'struct MeterGolden { int w,h,pattern; double ev[8]; };',
         'static const MeterGolden goldens[] = {']
for w, h, pattern in cases:
    i = np.arange(w * h * 3, dtype=np.int64)
    if pattern == 0:
        values = np.zeros_like(i, dtype=np.float64)
    elif pattern == 1:
        values = .002 + i % 11 * .0001
    elif pattern == 2:
        values = np.where((i // 3) % 101 == 0, 3.,
                          np.where((i // 3) % 13 == 0, .3 + i % 7 * .07, .004 + i % 17 * .0003))
    else:
        values = .001 + (i * 7919 % 4093) / 1701.
    image = values.astype(np.float32).astype(np.float64).reshape(h, w, 3)
    # The runtime measures the shared antialiased 256px small_preview.
    from skimage.transform import rescale
    if max(w, h) > 256:
        image = rescale(image, 256 / max(w, h), channel_axis=2, order=0)
    evs = [measure_autoexposure_ev(image, 'ProPhoto RGB', False, method) for method in methods]
    lines.append('    {' + f'{w},{h},{pattern},' + '{' + ','.join(format(v, '.17g') for v in evs) + '}},')
lines.append('};')
Path(__file__).with_name('metering_goldens.h').write_text('\n'.join(lines) + '\n')
