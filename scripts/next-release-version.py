#!/usr/bin/env python3
"""Choose the next test release from the source baseline and all fetched Git tags."""
import os
from pathlib import Path
import re
import subprocess

MAX_CODE = 2_100_000_000


def next_version(source, tags):
    baseline = re.search(r'defaultVersionName\s*=\s*"(\d+\.\d+\.\d+)(?:-test)?"', source)
    if not baseline:
        raise ValueError('defaultVersionName is missing from app/build.gradle.kts')
    versions = [tuple(map(int, baseline.group(1).split('.')))]
    for tag in tags:
        match = re.search(r'(\d+)\.(\d+)\.(\d+)(?:[-_]test)?$', tag)
        if match:
            versions.append(tuple(map(int, match.groups())))
    major, minor, patch = max(versions)
    if minor >= 1000 or patch >= 1000:
        raise ValueError('Release minor and patch must be below 1000 for Android versionCode encoding')
    patch += 1
    if patch >= 1000:
        minor, patch = minor + 1, 0
    if minor >= 1000:
        major, minor = major + 1, 0
    code = major * 1_000_000 + minor * 1000 + patch
    if not 0 < code <= MAX_CODE:
        raise ValueError('Generated versionCode exceeds the Android limit')
    name = f'{major}.{minor}.{patch}-test'
    return name, code, f'v{name}'


def main():
    source = Path('app/build.gradle.kts').read_text()
    tags = subprocess.check_output(['git', 'tag', '--list'], text=True).splitlines()
    name, code, tag = next_version(source, tags)
    if not os.environ.get('GITHUB_ENV') or not os.environ.get('GITHUB_OUTPUT'):
        print(f'{name} (versionCode={code}, tag={tag})')
        return
    with open(os.environ['GITHUB_ENV'], 'a') as stream:
        stream.write(f'LUMEN_VERSION_NAME={name}\nLUMEN_VERSION_CODE={code}\n')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
        stream.write(f'name={name}\ncode={code}\ntag={tag}\n')
    print(f'Next release: {tag}; Android versionCode: {code}')


if __name__ == '__main__':
    main()
