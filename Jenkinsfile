pipeline {
    agent any
    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 60, unit: 'MINUTES')
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
    }
    environment {
        TACZ_BRANCH = "${env.BRANCH_NAME}"
    }
    stages {
        stage('Configure CI') {
            steps {
                configFileProvider([configFile(fileId: 'tacz-ci-runtime', variable: 'TACZ_CI_RUNTIME')]) {
                    script {
                        def runtime = load env.TACZ_CI_RUNTIME
                        env.TACZ_NEXUS_READ_URL = runtime.TACZ_NEXUS_READ_URL
                        env.TACZ_NEXUS_PUBLISH_URL = runtime.TACZ_NEXUS_PUBLISH_URL
                        env.TACZ_NEXUS_CREDENTIALS_ID = runtime.TACZ_NEXUS_CREDENTIALS_ID
                        env.TACZ_JDK21_TOOL = runtime.TACZ_JDK21_TOOL
                        env.JDK17_HOME = runtime.JDK17_HOME
                        env.GRADLE_OPTS = runtime.GRADLE_OPTS
                        env.JAVA_HOME = tool(name: env.TACZ_JDK21_TOOL, type: 'hudson.model.JDK')
                        env.PATH = "${env.JAVA_HOME}/bin:${env.PATH}"
                    }
                }
            }
        }
        stage('Checkout') {
            steps {
                checkout scm
                sh 'git log -1 --format="%H %s" && java -version'
            }
        }
        stage('Prepare client mappings') {
            steps {
                script {
                    if (env.BRANCH_NAME.startsWith('1.20.1')) {
                        withEnv(["JAVA_HOME=${env.JDK17_HOME}", "PATH+JAVA17=${env.JDK17_HOME}/bin"]) {
                            sh 'sh ./gradlew --no-daemon --console=plain --init-script "$WORKSPACE/ci/nexus.init.gradle" clean'
                            sh 'sh ./gradlew --no-daemon --console=plain --init-script "$WORKSPACE/ci/nexus.init.gradle" createMcpToSrg ciVerifyMappedClient'
                        }
                    } else {
                        sh 'sh ./gradlew --no-daemon --console=plain --init-script "$WORKSPACE/ci/nexus.init.gradle" clean'
                        sh 'sh ./gradlew --no-daemon --console=plain --init-script "$WORKSPACE/ci/nexus.init.gradle" createMinecraftArtifacts ciVerifyMappedClient'
                    }
                }
            }
        }
        stage('Build client') {
            steps {
                withCredentials([usernamePassword(credentialsId: env.TACZ_NEXUS_CREDENTIALS_ID, usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')]) {
                    script {
                        if (env.BRANCH_NAME.startsWith('1.20.1')) {
                            withEnv(["JAVA_HOME=${env.JDK17_HOME}", "PATH+JAVA17=${env.JDK17_HOME}/bin"]) {
                                sh 'java -version && sh ./gradlew --no-daemon --console=plain --init-script "$WORKSPACE/ci/nexus.init.gradle" build jarJar ciArtifactManifest -x test'
                            }
                        } else {
                            sh "sh ./gradlew --no-daemon --console=plain --init-script '$WORKSPACE/ci/nexus.init.gradle' build ciArtifactManifest -x test ${env.BRANCH_NAME.startsWith('codex/') ? '-x buildPaper' : ''}"
                        }
                    }
                }
            }
        }
        stage('Build Paper') {
            steps {
                dir('paper') {
                    withCredentials([usernamePassword(credentialsId: env.TACZ_NEXUS_CREDENTIALS_ID, usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')]) {
                        sh 'sh ./gradlew --no-daemon --console=plain --init-script "$WORKSPACE/ci/nexus.init.gradle" clean build ciArtifactManifest -x test'
                    }
                }
            }
        }
        stage('Publish Nexus') {
            steps {
                withCredentials([usernamePassword(credentialsId: env.TACZ_NEXUS_CREDENTIALS_ID, usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')]) {
                    script {
                        if (env.BRANCH_NAME.startsWith('1.20.1')) {
                            withEnv(["JAVA_HOME=${env.JDK17_HOME}", "PATH+JAVA17=${env.JDK17_HOME}/bin"]) {
                                sh 'sh ./gradlew --no-daemon --console=plain --init-script "$WORKSPACE/ci/nexus.init.gradle" publishCiPublicationToNexusRepository -x test'
                            }
                        } else {
                            sh 'sh ./gradlew --no-daemon --console=plain --init-script "$WORKSPACE/ci/nexus.init.gradle" publishCiPublicationToNexusRepository -x test'
                        }
                    }
                    dir('paper') {
                        sh 'sh ./gradlew --no-daemon --console=plain --init-script "$WORKSPACE/ci/nexus.init.gradle" publishCiPublicationToNexusRepository -x test'
                    }
                }
            }
        }
        stage('Verify Nexus') {
            steps {
                withCredentials([usernamePassword(credentialsId: env.TACZ_NEXUS_CREDENTIALS_ID, usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')]) {
                    script {
                        if (env.BRANCH_NAME.startsWith('1.20.1')) {
                            withEnv(["JAVA_HOME=${env.JDK17_HOME}", "PATH+JAVA17=${env.JDK17_HOME}/bin"]) {
                                sh 'sh ./gradlew --no-daemon --console=plain --init-script "$WORKSPACE/ci/nexus.init.gradle" verifyCiPublicationOnNexus -x test'
                            }
                        } else {
                            sh 'sh ./gradlew --no-daemon --console=plain --init-script "$WORKSPACE/ci/nexus.init.gradle" verifyCiPublicationOnNexus -x test'
                        }
                    }
                    dir('paper') {
                        sh 'sh ./gradlew --no-daemon --console=plain --init-script "$WORKSPACE/ci/nexus.init.gradle" verifyCiPublicationOnNexus -x test'
                    }
                }
            }
        }
        stage('Archive') {
            steps {
                script {
                    def artifacts = (readFile('build/ci-archive.txt') + readFile('paper/build/ci-archive.txt')).trim().split('\n')
                    archiveArtifacts artifacts: artifacts.join(',') + ',build/ci-artifacts.json,paper/build/ci-artifacts.json', fingerprint: true
                }
            }
        }
    }
}
