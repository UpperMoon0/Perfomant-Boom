def prepareBoom(String heap) {
    if (env.CHANGE_ID || env.BRANCH_NAME != 'main') { error('Reviewed main only') }
    checkout scm
    def revision = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
    if (env.CI_BOOM_SHA && revision != env.CI_BOOM_SHA) { error('Source changed between CI stages') }
    env.CI_BOOM_SHA = revision
    env.SOURCE_SHA = revision
    env.CI_BOOM_SCRATCH = sh(returnStdout: true, script: 'mktemp -d /home/jenkins/agent/boom-ci-XXXXXX').trim()
    env.TMPDIR = env.CI_BOOM_SCRATCH
    env.GRADLE_USER_HOME = env.CI_BOOM_SCRATCH + '/gradle'
    env.JAVA_HOME = env.JAVA_HOME_21
    env.PATH = env.CI_BOOM_SCRATCH + '/bin:' + env.JAVA_HOME_21 + '/bin:' + env.PATH
    env.GRADLE_OPTS = '-Dorg.gradle.workers.max=1 -Dorg.gradle.jvmargs=-Xmx' + heap
    sh '''#!/usr/bin/env bash
set -euo pipefail
mkdir -p "$GRADLE_USER_HOME" "$CI_BOOM_SCRATCH/bin" build/ci-evidence
printf '%s\\n' "$CI_BOOM_SHA" > build/ci-evidence/source-sha.txt
printf 'org.gradle.java.installations.paths=%s,%s,%s,%s\\norg.gradle.java.installations.auto-download=false\\nsystemProp.maven.repo.local=%s/m2\\n' "$JAVA_HOME_8" "$JAVA_HOME_17" "$JAVA_HOME_21" "$JAVA_HOME_25" "$CI_BOOM_SCRATCH" > "$GRADLE_USER_HOME/gradle.properties"
chmod +x gradlew
'''
}

def cleanupBoom() {
    if (env.CI_BOOM_SCRATCH) {
        sh '''python3 - <<'PY'
import os,pathlib,shutil
path=pathlib.Path(os.environ['CI_BOOM_SCRATCH']).resolve()
if path.parent != pathlib.Path('/home/jenkins/agent') or not path.name.startswith('boom-ci-'):
    raise ValueError('Unexpected CI scratch directory')
shutil.rmtree(path)
PY'''
        env.CI_BOOM_SCRATCH = ''
    }
    cleanWs(deleteDirs: true, disableDeferredWipeout: true)
}

pipeline {
    agent none
    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        timestamps()
        timeout(time: 8, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '8', daysToKeepStr: '14'))
    }
    environment {
        LIBGL_ALWAYS_SOFTWARE = '1'
        ALSOFT_DRIVERS = 'null'
    }
    stages {
        stage('Release contracts, workflows and loader-neutral core') {
            agent { label 'ci-general && ci-linux-container && ci-memory-small' }
            options { timeout(time: 30, unit: 'MINUTES') }
            steps {
                script { prepareBoom('768m') }
                sh '''#!/usr/bin/env bash
set -euo pipefail
curl -fsSL --retry 2 https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz -o "$CI_BOOM_SCRATCH/actionlint.tar.gz"
curl -fsSL --retry 2 https://github.com/koalaman/shellcheck/releases/download/v0.10.0/shellcheck-v0.10.0.linux.x86_64.tar.xz -o "$CI_BOOM_SCRATCH/shellcheck.tar.xz"
printf '%s  %s\\n' 8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8 "$CI_BOOM_SCRATCH/actionlint.tar.gz" | sha256sum --check --strict
printf '%s  %s\\n' 6c881ab0698e4e6ea235245f22832860544f17ba386442fe7e9d629f8cbedf87 "$CI_BOOM_SCRATCH/shellcheck.tar.xz" | sha256sum --check --strict
tar -xzf "$CI_BOOM_SCRATCH/actionlint.tar.gz" -C "$CI_BOOM_SCRATCH/bin" actionlint
python3 - <<'PY'
import os,pathlib,shutil,tarfile
root=pathlib.Path(os.environ['CI_BOOM_SCRATCH'])
with tarfile.open(root/'shellcheck.tar.xz','r:xz') as archive:
    member=archive.getmember('shellcheck-v0.10.0/shellcheck')
    if not member.isfile() or member.size > 64*1024*1024:
        raise ValueError('Unexpected ShellCheck archive entry')
    with archive.extractfile(member) as source,(root/'bin/shellcheck').open('wb') as target:
        shutil.copyfileobj(source,target)
(root/'bin/shellcheck').chmod(0o755)
PY
python3 -m unittest discover -s tools -p 'test_*.py' -v 2>&1 | tee build/ci-evidence/tooling-tests.log
python3 tools/release.py check
actionlint -color
./gradlew :core:check --stacktrace --no-daemon --max-workers=1 --console=plain 2>&1 | tee build/ci-evidence/core.log
'''
            }
            post {
                always {
                    script {
                        try {
                            archiveArtifacts artifacts: 'build/ci-evidence/**,core/build/reports/**,core/build/test-results/**', allowEmptyArchive: true
                            junit testResults: 'core/build/test-results/test/*.xml', allowEmptyResults: false
                        } finally { cleanupBoom() }
                    }
                }
            }
        }
        stage('Complete native and live acceptance') {
            agent { label 'ci-linux-container && ci-memory-large' }
            options { timeout(time: 3, unit: 'HOURS') }
            stages {
                stage('Exact source and production matrix') {
                    steps {
                        script { prepareBoom('1536m') }
                        timeout(time: 40, unit: 'MINUTES') {
                            sh '''#!/usr/bin/env bash
set -euo pipefail
./gradlew buildAll -I .github/reproducible.gradle --stacktrace --no-daemon --max-workers=1 --console=plain 2>&1 | tee build/ci-evidence/build-all.log
./gradlew :forge:runBoomGameTestServer --no-daemon --console=plain --max-workers=1 2>&1 | tee build/ci-evidence/scheduler-gametest.log
python3 tools/release.py package
python3 tools/release.py verify
'''
                        }
                        archiveArtifacts artifacts: 'build/release/*', fingerprint: true
                    }
                }
                stage('Frozen Fabric server, client and save reload') {
                    steps {
                        dir('live-fabric') {
                            checkout scm
                            sh '''#!/usr/bin/env bash
set -euo pipefail
test "$(git rev-parse HEAD)" = "$CI_BOOM_SHA"
chmod +x gradlew
mkdir -p build/ci-evidence
'''
                            timeout(time: 40, unit: 'MINUTES') {
                                sh '''#!/usr/bin/env bash
set -euo pipefail
python3 tools/live_boom_test.py --loader fabric --timeout 900 --require-clean 2>&1 | tee build/ci-evidence/live.log
'''
                            }
                        }
                    }
                }
                stage('Frozen Forge server, client and save reload') {
                    steps {
                        dir('live-forge') {
                            checkout scm
                            sh '''#!/usr/bin/env bash
set -euo pipefail
test "$(git rev-parse HEAD)" = "$CI_BOOM_SHA"
chmod +x gradlew
mkdir -p build/ci-evidence
'''
                            timeout(time: 40, unit: 'MINUTES') {
                                sh '''#!/usr/bin/env bash
set -euo pipefail
python3 tools/live_boom_test.py --loader forge --timeout 900 --require-clean 2>&1 | tee build/ci-evidence/live.log
'''
                            }
                        }
                    }
                }
            }
            post {
                always {
                    script {
                        try {
                            archiveArtifacts artifacts: 'build/release/*,build/ci-evidence/**,**/build/reports/**,**/build/test-results/**,live-*/build/live-boom-evidence/**,live-*/build/ci-evidence/**,live-*/**/run/live-boom/**/crash-reports/**', allowEmptyArchive: true
                            junit testResults: '**/build/test-results/**/*.xml', allowEmptyResults: true
                        } finally { cleanupBoom() }
                    }
                }
            }
        }
    }
}
