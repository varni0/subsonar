import glob,re,sys
import xml.etree.ElementTree as ET
pat=sys.argv[1] if len(sys.argv)>1 else '*'
for f in glob.glob(f'/workspace/subpilot/app/build/test-results/testDebugUnitTest/TEST-*{pat}*.xml'):
    t=ET.parse(f).getroot()
    print(f.split('/')[-1], t.get('tests'), 'fail', t.get('failures'), 'skip', t.get('skipped'))
    for tc in t.findall('testcase'):
        for fl in tc.findall('failure'):
            print(' -', tc.get('name'), '\n   ', (fl.get('message') or '')[:700])
