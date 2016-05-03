VAGRANTFILE_API_VERSION = "2"

Vagrant.configure(VAGRANTFILE_API_VERSION) do |config|
  config.vm.synced_folder ".", "/vagrant", id: "vagrant-root", disabled: true
  config.vm.box = "freebsd/FreeBSD-10.3-RELEASE"
  config.ssh.shell = "sh"
  config.vm.base_mac = "080027D14C66"

  config.vm.network :forwarded_port, guest: 8983, host: 8983

  config.vm.provider :virtualbox do |vbox|
    vbox.customize ["modifyvm", :id, "--memory", 512]
  end

  config.vm.provision :shell, inline: "pkg update -f"
  config.vm.provision :shell, inline: "pkg install -fy openjdk8-jre"
  config.vm.provision :shell, inline: "fetch http://mirror.softaculous.com/apache/lucene/solr/5.2.1/solr-5.2.1.tgz"
  config.vm.provision :shell, inline: "tar zxvpf solr-5.2.1.tgz"

end
