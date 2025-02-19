#!/usr/bin/env groovy
// CLEANUP REQUIRED
pipeline { 
    agent {
        node {
            label "docker-${OS_VERSION}-node"
        }
    }

    options { 
        skipDefaultCheckout()
        timeout(time: 180, unit: 'MINUTES')
        timestamps()
        disableConcurrentBuilds()
        ansiColor('xterm')  
    }

    parameters {  
	    string(name: 'MOTR_URL', defaultValue: 'https://github.com/Seagate/cortx-motr', description: 'Repo for Motr')
        string(name: 'MOTR_BRANCH', defaultValue: 'main', description: 'Branch for Motr')
        choice(name: 'DEPLOY_BUILD_ON_NODES', choices: ["Both", "1node", "3node" ], description: '''<pre>If you select Both then build will be deploy on 1 node as well as 3 node. If you select 1 node then build will be deploy on 1 node only. If you select 3 node then build will be deploy on 3 node only. 
</pre>''')
        string(name: 'CORTX_RE_URL', defaultValue: 'https://github.com/Seagate/cortx-re', description: 'Repo for cortx-re')
        string(name: 'CORTX_RE_BRANCH', defaultValue: 'main', description: 'Branch for cortx-re')

        choice (
            choices: ['all', 'cortx-rgw', 'cortx-data', 'cortx-control'],
            description: 'CORTX Image to be built. Defaults to all images ',
            name: 'CORTX_IMAGE'
        )
	}

    environment {

        // Motr Repo Info

        GPR_REPO = "https://github.com/${ghprbGhRepository}"
        MOTR_URL = "${ghprbGhRepository != null ? GPR_REPO : MOTR_URL}"
        MOTR_BRANCH = "${sha1 != null ? sha1 : MOTR_BRANCH}"

        MOTR_GPR_REFSEPEC = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
        MOTR_BRANCH_REFSEPEC = "+refs/heads/*:refs/remotes/origin/*"
        MOTR_PR_REFSEPEC = "${ghprbPullId != null ? MOTR_GPR_REFSEPEC : MOTR_BRANCH_REFSEPEC}"

        //////////////////////////////// BUILD VARS //////////////////////////////////////////////////
        // OS_VERSION, singlenode_host, threenode_hosts, COMPONENTS_BRANCH are manually created parameters in jenkins job.

        COMPONENT_NAME = "motr".trim()
        BRANCH = "${ghprbTargetBranch != null ? ghprbTargetBranch : COMPONENTS_BRANCH}"
        THIRD_PARTY_VERSION = "${OS_VERSION}-2.0.0-k8"
        VERSION = "2.0.0"
        RELEASE_TAG = "last_successful_prod"
        PASSPHARASE = credentials('rpm-sign-passphrase')

        OS_FAMILY=sh(script: "echo '${OS_VERSION}' | cut -d '-' -f1", returnStdout: true).trim()

        // 'WARNING' - rm -rf command used on this path please careful when updating this value
        DESTINATION_RELEASE_LOCATION = "/mnt/bigstorage/releases/cortx/github/pr-build/${BRANCH}/${COMPONENT_NAME}/${BUILD_NUMBER}"
        PYTHON_DEPS = "/mnt/bigstorage/releases/cortx/third-party-deps/python-deps/python-packages-2.0.0-latest"
        THIRD_PARTY_DEPS = "/mnt/bigstorage/releases/cortx/third-party-deps/${OS_FAMILY}/${THIRD_PARTY_VERSION}/"
        COMPONENTS_RPM = "/mnt/bigstorage/releases/cortx/components/github/${BRANCH}/${OS_VERSION}/dev/"
        CORTX_BUILD = "http://cortx-storage.colo.seagate.com/releases/cortx/github/pr-build/${BRANCH}/${COMPONENT_NAME}/${BUILD_NUMBER}"

        // Artifacts location
        CORTX_ISO_LOCATION = "${DESTINATION_RELEASE_LOCATION}/cortx_iso"
        THIRD_PARTY_LOCATION = "${DESTINATION_RELEASE_LOCATION}/3rd_party"
        PYTHON_LIB_LOCATION = "${DESTINATION_RELEASE_LOCATION}/python_deps"

        ////////////////////////////////// DEPLOYMENT VARS /////////////////////////////////////////////////////

        // NODE1_HOST - Env variables added in the node configurations
        build_identifier = sh(script: "echo ${CORTX_BUILD} | rev | cut -d '/' -f2,3 | rev", returnStdout: true).trim()

        STAGE_DEPLOY = "yes"
    }

    stages {

        // Build motr fromm PR source code
        stage('Build') {
            steps {
				script { build_stage = env.STAGE_NAME }
                script { manager.addHtmlBadge("&emsp;<b>Target Branch : ${BRANCH}</b>&emsp;<br />") }

                 sh """
                    set +x
                    echo "--------------BUILD PARAMETERS -------------------"
                    echo "MOTR_URL              = ${MOTR_URL}"
                    echo "MOTR_BRANCH           = ${MOTR_BRANCH}"
                    echo "MOTR_PR_REFSEPEC       = ${MOTR_PR_REFSEPEC}"
                    echo "-----------------------------------------------------------"
                """
                 
                dir("motr") {

                    checkout([$class: 'GitSCM', branches: [[name: "${MOTR_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'AuthorInChangelog'], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'cortx-admin-github', url: "${MOTR_URL}",  name: 'origin', refspec: "${MOTR_PR_REFSEPEC}"]]])

                    sh label: '', script: '''
                    yum-config-manager --add-repo=http://cortx-storage.colo.seagate.com/releases/cortx/third-party-deps/rockylinux/rockylinux-8.4-2.0.0-latest/
                    yum --nogpgcheck -y --disablerepo="EOS_Rocky_8_OS_x86_64_Rocky_8" install libfabric-1.11.2 libfabric-devel-1.11.2
                    '''

                    sh label: '', script: '''
                        export build_number=${BUILD_ID}
                        cp cortx-motr.spec.in cortx-motr.spec
                        sed -i "/BuildRequires.*kernel*/d" cortx-motr.spec
                        sed -i "/BuildRequires.*%{lustre_devel}/d" cortx-motr.spec
                        sed -i 's/@BUILD_DEPEND_LIBFAB@//g' cortx-motr.spec  
                        sed -i 's/@.*@/111/g' cortx-motr.spec
                        yum-builddep -y --nogpgcheck cortx-motr.spec
					'''

                    sh label: '', script: '''
						rm -rf /root/rpmbuild/RPMS/x86_64/*.rpm
						./autogen.sh
						./configure --with-user-mode-only
						export build_number=${BUILD_ID}
						make rpms
					'''

                    sh label: 'Copy RPMS', script: '''
                        mkdir -p "/root/build_rpms"  
                        cp /root/rpmbuild/RPMS/x86_64/*.rpm "/root/build_rpms"  
                    '''
                }

                dir ('hare') {

                    checkout([$class: 'GitSCM', branches: [[name: "*/${BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false,  timeout: 5], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'cortx-admin-github', url: 'https://github.com/Seagate/cortx-hare.git']]])

                    sh label: '', script: '''
                        yum erase python36-PyYAML -y
                        cat <<EOF >>/etc/pip.conf
[global]
timeout: 60
index-url: http://cortx-storage.colo.seagate.com/releases/cortx/third-party-deps/python-deps/python-packages-2.0.0-latest/
trusted-host: cortx-storage.colo.seagate.com
EOF
                        pip3 install -r https://raw.githubusercontent.com/Seagate/cortx-utils/$BRANCH/py-utils/python_requirements.txt
                        pip3 install -r https://raw.githubusercontent.com/Seagate/cortx-utils/$BRANCH/py-utils/python_requirements.ext.txt
                        rm -rf /etc/pip.conf
                    '''

                    // Build Hare
                    sh label: '', script: '''
                        yum-config-manager --add-repo=http://cortx-storage.colo.seagate.com/releases/cortx/github/$BRANCH/$OS_VERSION/$RELEASE_TAG/cortx_iso/
                        yum-config-manager --save --setopt=cortx-storage*.gpgcheck=1 cortx-storage* && yum-config-manager --save --setopt=cortx-storage*.gpgcheck=0 cortx-storage*
                        yum clean all;rm -rf /var/cache/yum
                        yum install cortx-py-utils -y
                        pushd /root/build_rpms
                            yum localinstall cortx-motr-2*.rpm cortx-motr-devel-2*.rpm -y
                        popd    
                    '''

                        sh label: 'Build', returnStatus: true, script: '''
                            set -xe
                            echo "Executing build script"
                            export build_number=${BUILD_NUMBER}
                            make VERSION=$VERSION rpm
                        '''	
                        sh label: 'Copy RPMS', script: '''
                            cp /root/rpmbuild/RPMS/x86_64/*.rpm /root/build_rpms
                        '''
                    
                    }

                }
        }

        // Release cortx deployment stack
        stage('Release') {
            steps {
				script { build_stage = env.STAGE_NAME }

                dir('cortx-re') {
                    checkout([$class: 'GitSCM', branches: [[name: '*/main']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', depth: 1, honorRefspec: true, noTags: true, reference: '', shallow: true], [$class: 'AuthorInChangelog']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'cortx-admin-github', url: 'https://github.com/Seagate/cortx-re']]])
                }

                //Install tools required for release process
                sh label: 'Installed Dependecies', script: '''
                    yum install -y expect rpm-sign rng-tools python3-pip
                    # ln -fs $(which python3.6) /usr/bin/python2 
                    # systemctl start rngd
                '''

                // Integrate components rpms
                sh label: 'Collect Release Artifacts', script: '''
                    
                    rm -rf "${DESTINATION_RELEASE_LOCATION}"
                    mkdir -p "${DESTINATION_RELEASE_LOCATION}"
                    if [[ ( ! -z `ls /root/build_rpms/*.rpm `)]]; then
                        mkdir -p "${CORTX_ISO_LOCATION}"
                        cp /root/build_rpms/*.rpm "${CORTX_ISO_LOCATION}"
                    else
                        echo "RPM not exists !!!"
                        exit 1
                    fi 
                    pushd ${COMPONENTS_RPM}
                        for component in `ls -1 | grep -E -v "motr|hare"`
                        do
                            echo -e "Copying RPM's for $component"
                            if ls $component/last_successful/*.rpm 1> /dev/null 2>&1; then
                                cp $component/last_successful/*.rpm "${CORTX_ISO_LOCATION}"
                            fi
                        done
                    popd
                    # Symlink 3rdparty repo artifacts
                    ln -s "${THIRD_PARTY_DEPS}" "${THIRD_PARTY_LOCATION}"
                        
                    # Symlink python dependencies
                    ln -s "${PYTHON_DEPS}" "${PYTHON_LIB_LOCATION}"
                '''

                sh label: 'Repo Creation', script: '''
                    pushd ${CORTX_ISO_LOCATION}
                        yum install -y createrepo
                        createrepo .
                    popd
                '''	

                sh label: 'Generate RELEASE.INFO', script: '''
                    pushd cortx-re/scripts/release_support
                        sh build_release_info.sh -v ${VERSION} -l ${CORTX_ISO_LOCATION} -t ${THIRD_PARTY_LOCATION}
                        sed -i -e 's/BRANCH:.*/BRANCH: "motr-pr"/g' ${CORTX_ISO_LOCATION}/RELEASE.INFO
                        sh build_readme.sh "${DESTINATION_RELEASE_LOCATION}"
                    popd
                    cp "${THIRD_PARTY_LOCATION}/THIRD_PARTY_RELEASE.INFO" "${DESTINATION_RELEASE_LOCATION}"
                    cp "${CORTX_ISO_LOCATION}/RELEASE.INFO" "${DESTINATION_RELEASE_LOCATION}"
                    cp "${CORTX_ISO_LOCATION}/RELEASE.INFO" .
                '''	

                archiveArtifacts artifacts: "RELEASE.INFO", onlyIfSuccessful: false, allowEmptyArchive: true
            }

        }

        // Deploy Cortx-Stack
        stage ("Build CORTX Images") {
            steps {
                script { build_stage = env.STAGE_NAME }
                script {
                    try {
                        def buildCortxAllImage = build job: 'cortx-docker-images-for-PR', wait: true,
                            parameters: [
                                string(name: 'CORTX_RE_URL', value: "${CORTX_RE_URL}"),
                                string(name: 'CORTX_RE_BRANCH', value: "${CORTX_RE_BRANCH}"),
                                string(name: 'BUILD', value: "${CORTX_BUILD}"),
                                string(name: 'OS', value: "${OS_VERSION}"),
                                string(name: 'CORTX_IMAGE', value: "${CORTX_IMAGE}"),
                                string(name: 'GITHUB_PUSH', value: "yes"),
                                string(name: 'TAG_LATEST', value: "no"),
                                string(name: 'DOCKER_REGISTRY', value: "cortx-docker.colo.seagate.com"),
                                string(name: 'EMAIL_RECIPIENTS', value: "DEBUG")
                            ]
                        env.cortx_rgw_image = buildCortxAllImage.buildVariables.cortx_rgw_image
                        env.cortx_data_image = buildCortxAllImage.buildVariables.cortx_data_image
                        env.cortx_control_image = buildCortxAllImage.buildVariables.cortx_control_image
                    } catch (err) {
                        build_stage = env.STAGE_NAME
                        error "Failed to Build CORTX-ALL image"
                    }
                }
            }
        }
	stage ('Deploy Cortx Cluster') {
             parallel {
                  stage ("Deploy 1Node") {
                       when { expression { params.DEPLOY_BUILD_ON_NODES ==~ /Both|1node/ } }
                       steps {
                             script { build_stage = env.STAGE_NAME }
                             script {
                                  build job: "K8s-1N-deployment", wait: true,
                                  parameters: [
                                        string(name: 'CORTX_RE_REPO', value: "${CORTX_RE_URL}"),
                                        string(name: 'CORTX_RE_BRANCH', value: "${CORTX_RE_BRANCH}"),
                                        string(name: 'CORTX_SERVER_IMAGE', value: "${env.cortx_rgw_image}"),
                                        string(name: 'CORTX_DATA_IMAGE', value: "${env.cortx_data_image}"),
                                        string(name: 'CORTX_CONTROL_IMAGE', value: "${env.cortx_control_image}"),
                                        string(name: 'CORTX_SCRIPTS_REPO', value: "Seagate/cortx-k8s"),
                                        string(name: 'hosts', value: "${singlenode_host}"),
                                        string(name: 'EMAIL_RECIPIENTS', value: "DEBUG")
                                 ] 
                             }
                       }
                } 
                stage ("Deploy 3Node") {
                       when { expression { params.DEPLOY_BUILD_ON_NODES ==~ /Both|3node/ } }
                       steps {
                             script { build_stage = env.STAGE_NAME }
                             script {
                                  build job: "K8s-3N-deployment", wait: true,
                                  parameters: [
                                        string(name: 'CORTX_RE_REPO', value: "${CORTX_RE_URL}"),
                                        string(name: 'CORTX_RE_BRANCH', value: "${CORTX_RE_BRANCH}"),
                                        string(name: 'CORTX_SERVER_IMAGE', value: "${env.cortx_rgw_image}"),
                                        string(name: 'CORTX_DATA_IMAGE', value: "${env.cortx_data_image}"),
                                        string(name: 'CORTX_CONTROL_IMAGE', value: "${env.cortx_control_image}"),
                                        string(name: 'CORTX_SCRIPTS_REPO', value: "Seagate/cortx-k8s"),
                                        string(name: 'hosts', value: "${threenode_hosts}"),
                                        string(name: 'EMAIL_RECIPIENTS', value: "DEBUG")
                                 ] 
                             }
                       }
                }
             }
        }
    }

    post {
        always {
            script {
                sh label: 'Remove artifacts', script: '''rm -rf "${DESTINATION_RELEASE_LOCATION}"'''
            }
        }
        failure {
            script {
                manager.addShortText("${build_stage} Failed")
            }  
        }
    }
}