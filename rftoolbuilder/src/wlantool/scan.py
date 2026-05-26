#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import sys
import subprocess
import os
import re
import codecs
from typing import Dict
import wcwidth


class WiFiScanner:
    """WiFi network scanner using iw"""

    def __init__(self, interface, vuln_list=None):
        self.interface = interface
        self.vuln_list = vuln_list or []

    def iw_scanner(self) -> Dict[int, dict]:
        """Parsing iw scan results"""

        def handle_network(line, result, networks):
            networks.append({
                'Security type': 'Unknown',
                'WPS': False,
                'WPS locked': False,
                'Model': '',
                'Model number': '',
                'Device name': ''
            })
            networks[-1]['BSSID'] = result.group(1).upper()

        def handle_essid(line, result, networks):
            d = result.group(1)
            networks[-1]['ESSID'] = codecs.decode(d, 'unicode-escape').encode('latin1').decode('utf-8', errors='replace')

        def handle_level(line, result, networks):
            networks[-1]['Level'] = int(float(result.group(1)))

        def handle_securityType(line, result, networks):
            sec = networks[-1]['Security type']
            if result.group(1) == 'capability':
                if 'Privacy' in result.group(2):
                    sec = 'WEP'
                else:
                    sec = 'Open'
            elif sec == 'WEP':
                if result.group(1) == 'RSN':
                    sec = 'WPA2'
                elif result.group(1) == 'WPA':
                    sec = 'WPA'
            elif sec == 'WPA':
                if result.group(1) == 'RSN':
                    sec = 'WPA/WPA2'
            elif sec == 'WPA2':
                if result.group(1) == 'WPA':
                    sec = 'WPA/WPA2'
            networks[-1]['Security type'] = sec

        def handle_wps(line, result, networks):
            networks[-1]['WPS'] = result.group(1)

        def handle_wpsLocked(line, result, networks):
            flag = int(result.group(1), 16)
            if flag:
                networks[-1]['WPS locked'] = True

        def handle_model(line, result, networks):
            d = result.group(1)
            networks[-1]['Model'] = codecs.decode(d, 'unicode-escape').encode('latin1').decode('utf-8', errors='replace')

        def handle_modelNumber(line, result, networks):
            d = result.group(1)
            networks[-1]['Model number'] = codecs.decode(d, 'unicode-escape').encode('latin1').decode('utf-8', errors='replace')

        def handle_deviceName(line, result, networks):
            d = result.group(1)
            networks[-1]['Device name'] = codecs.decode(d, 'unicode-escape').encode('latin1').decode('utf-8', errors='replace')

        cmd = 'iw dev {} scan'.format(self.interface)
        proc = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, encoding='utf-8', errors='replace')
        lines = proc.stdout.splitlines()
        networks = []
        matchers = {
            re.compile(r'BSS (\S+)( )?\(on \w+\)'): handle_network,
            re.compile(r'SSID: (.*)'): handle_essid,
            re.compile(r'signal: ([+-]?([0-9]*[.])?[0-9]+) dBm'): handle_level,
            re.compile(r'(capability): (.+)'): handle_securityType,
            re.compile(r'(RSN):\t [*] Version: (\d+)'): handle_securityType,
            re.compile(r'(WPA):\t [*] Version: (\d+)'): handle_securityType,
            re.compile(r'WPS:\t [*] Version: (([0-9]*[.])?[0-9]+)'): handle_wps,
            re.compile(r' [*] AP setup locked: (0x[0-9]+)'): handle_wpsLocked,
            re.compile(r' [*] Model: (.*)'): handle_model,
            re.compile(r' [*] Model Number: (.*)'): handle_modelNumber,
            re.compile(r' [*] Device name: (.*)'): handle_deviceName,
        }

        for line in lines:
            if line.startswith('command failed:'):
                print('[!] Error:', line)
                return False
            line = line.strip('\t')
            for regexp, handler in matchers.items():
                res = re.match(regexp, line)
                if res:
                    handler(line, res, networks)

        networks = list(filter(lambda x: bool(x['WPS']), networks))
        if not networks:
            return False

        networks.sort(key=lambda x: x['Level'], reverse=True)
        network_list = {(i + 1): network for i, network in enumerate(networks)}

        def truncateStr(s, length, postfix="…"):
            original_width = wcwidth.wcswidth(s)
            if original_width <= length:
                padding_needed = length - original_width
                return s + ' ' * padding_needed
            postfix_width = wcwidth.wcswidth(postfix)
            max_allowed = length - postfix_width
            current_width = 0
            truncated = []
            for c in s:
                char_width = wcwidth.wcswidth(c)
                if current_width + char_width > max_allowed:
                    break
                truncated.append(c)
                current_width += char_width
            result = "".join(truncated)
            if len(truncated) < len(s):
                result += postfix
            result_width = wcwidth.wcswidth(result)
            if result_width > length:
                current_width = 0
                safe_truncated = []
                for c in result:
                    char_width = wcwidth.wcswidth(c)
                    if current_width + char_width > length:
                        break
                    safe_truncated.append(c)
                    current_width += char_width
                safe_result = "".join(safe_truncated)
                if len(safe_result) < len(result):
                    safe_result += postfix
                    if wcwidth.wcswidth(safe_result) > length:
                        safe_result = safe_result[:-1]
                return safe_result
            padding_needed = length - result_width
            return result + ' ' * padding_needed

        def colored(text, color=None):
            if color:
                if color == 'green':
                    text = '\033[92m{}\033[00m'.format(text)
                elif color == 'red':
                    text = '\033[91m{}\033[00m'.format(text)
            return text

        if self.vuln_list:
            print('Network marks: {1} {0} {2}'.format(
                '|',
                colored('Possibly vulnerable', color='green'),
                colored('WPS locked', color='red')
            ))
        print('Networks list:')
        print('{:<4} {:<18} {:<25} {:<8} {:<4} {:<27} {:<}'.format(
            '#', 'BSSID', 'ESSID', 'Sec.', 'PWR', 'WSC device name', 'WSC model'))

        for n, network in network_list.items():
            number = f'{n})'
            model = '{} {}'.format(network['Model'], network['Model number'])
            essid = truncateStr(network.get('ESSID', 'HIDDEN'), 25)
            deviceName = truncateStr(network['Device name'], 27)
            processed_number = truncateStr(number, 4)
            processed_bssid = truncateStr(network['BSSID'], 18)
            processed_security = truncateStr(network['Security type'], 8)
            processed_level = truncateStr(str(network['Level']), 4)
            line = ' '.join([
                processed_number,
                processed_bssid,
                essid,
                processed_security,
                processed_level,
                deviceName,
                model
            ])
            if network['WPS locked']:
                print(colored(line, color='red'))
            elif self.vuln_list and (model in self.vuln_list):
                print(colored(line, color='green'))
            else:
                print(line)

        return network_list


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


if __name__ == '__main__':
    import argparse

    parser = argparse.ArgumentParser(
        description='WiFi Scanner — scan nearby WPS-enabled networks',
        epilog='Example: %(prog)s -i wlan0 -scan'
    )
    parser.add_argument(
        '-i', '--interface',
        type=str,
        required=True,
        help='Name of the wireless interface to use (e.g. wlan0)'
    )
    parser.add_argument(
        '-scan',
        action='store_true',
        required=True,
        help='Scan for nearby WPS-enabled networks'
    )
    parser.add_argument(
        '--vuln-list',
        type=str,
        default=os.path.dirname(os.path.realpath(__file__)) + '/vulnwsc.txt',
        help='Path to custom vulnerable devices list file'
    )

    args = parser.parse_args()

    if sys.hexversion < 0x03060F0:
        die("The program requires Python 3.6 and above")
    if os.getuid() != 0:
        die("Run it as root")

    vuln_list = []
    try:
        with open(args.vuln_list, 'r', encoding='utf-8') as f:
            vuln_list = f.read().splitlines()
    except FileNotFoundError:
        pass

    scanner = WiFiScanner(args.interface, vuln_list)
    result = scanner.iw_scanner()
    if not result:
        print('[-] No WPS networks found.')
