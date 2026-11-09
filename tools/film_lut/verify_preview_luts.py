#!/usr/bin/env python3
"""Verify shipped LUT hashes, shape, and film/print pairing with stdlib only."""
import hashlib
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'assets/spektra'
DIRECTORY = ASSETS / 'preview_luts'
SHARED_SAMPLES = ASSETS / 'samples'
PRINT_VARIANTS = ASSETS / 'print_variants'
PRINT_SAMPLES = ASSETS / 'film_samples'


def verify_hashes(directory, expected):
    hashes = json.loads((directory / 'SHA256SUMS.json').read_text())
    assert set(hashes) == expected
    for name, digest in hashes.items():
        assert hashlib.sha256((directory / name).read_bytes()).hexdigest() == digest, name


def verify_photos(directory, photos):
    verify_hashes(directory, photos)
    assert {path.name for path in directory.glob('*.jpg')} == photos
    for name in photos:
        assert (directory / name).read_bytes().startswith(b'\xff\xd8\xff'), name


def verify():
    manifest = json.loads((DIRECTORY / 'manifest.json').read_text())
    assert manifest['schema'] == 1 and manifest['size'] == 65
    assert manifest['order'] == 'red-fast' and not manifest['autoExposure'] and not manifest['spatialEffects']
    catalog = json.loads((ASSETS / 'catalog.json').read_text())['stocks']
    expected = {key for key, value in catalog.items() if value['group'] in {
        'color_negative', 'color_reversal', 'motion_picture_negative'}}
    assert len(expected) == 20 and {entry['film'] for entry in manifest['looks']} == expected
    assert len(manifest['looks']) == 20
    for entry in manifest['looks']:
        profile = json.loads((ASSETS / 'profiles' / (entry['film'] + '.json')).read_text())['info']
        assert entry['print'] == ('' if profile['type'] == 'positive' else profile['target_print'])
        data = (DIRECTORY / (entry['film'] + '.plut')).read_bytes()
        assert struct.unpack_from('<4sIII', data) == (b'PLUT', 1, 65, 1)
        assert len(data) == 16 + 65**3 * 6
        assert entry['samples'] == 4096 and entry['rmse'] < .004 and entry['p99'] < .015
    verify_hashes(DIRECTORY, {'manifest.json'} | {key + '.plut' for key in expected})
    verify_photos(SHARED_SAMPLES, {key + '.jpg' for key in expected})
    print('PASS: 20 shared preview LUTs and graded sample photos')


def verify_prints():
    manifest = json.loads((PRINT_VARIANTS / 'manifest.json').read_text())
    default = json.loads((DIRECTORY / 'manifest.json').read_text())
    catalog = json.loads((ASSETS / 'catalog.json').read_text())['stocks']
    neutral = json.loads((ASSETS / 'filters/neutral_print_filters.json').read_text())
    print_groups = {'color_negative': 'print_paper', 'motion_picture_negative': 'print_film'}
    expected = {
        (entry['film'], paper)
        for entry in default['looks']
        if entry['print']
        for paper, stock in catalog.items()
        if stock['group'] == print_groups[catalog[entry['film']]['group']] and paper != entry['print']
    }
    actual = {(entry['film'], entry['print']) for entry in manifest['looks']}
    assert manifest['schema'] == 1 and manifest['engineCommit'] == default['engineCommit']
    assert manifest['order'] == 'red-fast' and not manifest['autoExposure'] and not manifest['spatialEffects']
    assert actual == expected and len(manifest['looks']) == len(expected) == 60
    files = {'manifest.json'}
    photos = set()
    for entry in manifest['looks']:
        film, paper, size = entry['film'], entry['print'], entry['size']
        assert film in neutral[paper]['TH-KG3'], (film, paper)
        assert size in (33, 49, 65) and entry['samples'] == 4096
        assert entry['rmse'] < .004 and entry['p99'] < .015
        key = film + '--' + paper
        lut = key + '.plut'
        data = (PRINT_VARIANTS / lut).read_bytes()
        assert struct.unpack_from('<4sIII', data) == (b'PLUT', 1, size, 1)
        assert len(data) == 16 + size**3 * 6
        files.add(lut)
        photos.add(key + '.jpg')
    verify_hashes(PRINT_VARIANTS, files)
    verify_photos(PRINT_SAMPLES, photos)
    print('PASS: 60 shared print variant LUTs and graded sample photos')


if __name__ == '__main__':
    verify()
    verify_prints()
