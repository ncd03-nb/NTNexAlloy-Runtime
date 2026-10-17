#!/usr/bin/env python3
"""Add the original module's ROM service protocol to SettingsProvider transport.

Only nt.nexalloy.* calls on the real settings authority are handled. Ordinary
provider requests keep Android's original path and validation.
"""
from pathlib import Path
import argparse
import re

MARKER = '->providerCall(Landroid/content/ContentProvider;'
SIGNATURE = ('call(Landroid/content/AttributionSource;Ljava/lang/String;'
             'Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;')


def patch(path):
    text = path.read_text(encoding='utf-8')
    if MARKER in text:
        return False
    methods = list(re.finditer(r'^\.method[^\n]* ' + re.escape(SIGNATURE) + r'\n.*?^\.end method', text, re.M | re.S))
    if len(methods) != 1:
        raise ValueError(f'Expected one provider call transport in {path}')
    match = methods[0]
    method = match.group()
    regs = re.search(r'\.registers (\d+)', method)
    if not regs or not 7 <= int(regs[1]) <= 16:
        raise ValueError('Unsupported provider transport register layout')
    validation = re.search(r'^    invoke-(?:static|direct)[^\n]*validateIncomingAuthority[^\n]*\n', method, re.M)
    if not validation or 'iget-object v0, p0, Landroid/content/ContentProvider$Transport;->this$0:Landroid/content/ContentProvider;' not in method[:validation.start()]:
        raise ValueError('Provider authority validation / outer provider layout changed')
    hook = '''
    invoke-static {v0, p2, p3, p4, p5}, Landroid/security/ntmanager/NexAlloyRuntime;->providerCall(Landroid/content/ContentProvider;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;
    move-result-object v0
    if-eqz v0, :nt_original_provider_call
    return-object v0
    :nt_original_provider_call
'''
    method = method[:validation.end()] + hook + method[validation.end():]
    path.write_text(text[:match.start()] + method + text[match.end():], encoding='utf-8')
    return True


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tree', type=Path)
    args = parser.parse_args()
    files = list(args.tree.rglob('android/content/ContentProvider$Transport.smali'))
    if len(files) != 1:
        parser.error(f'Expected exactly one ContentProvider transport, found {len(files)}')
    print('patched' if patch(files[0]) else 'already patched', files[0])
