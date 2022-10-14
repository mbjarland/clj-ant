(ns clj-ant.tasks
  (:require
   [clj-ant.core :as c]))

;; generated at: Wed Oct 31 14:21:14 CET 2018

(defn allbutfirst
 "Documentation for allbutfirst to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :allbutfirst args)) 


(defn allbutlast
 "Documentation for allbutlast to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :allbutlast args)) 


(defn antant
 "Runs Ant on a supplied buildfile, optionally passing properties (with
  possibly new values). This task can be used to build sub-projects."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ant args)) 


(defn antcall
 "Runs another target within the same buildfile, optionally passing
  properties (with possibly new values)."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :antcall args)) 


(defn antstructure
 "Generates a DTD for Ant buildfiles that contains information about all
  tasks currently known to Ant."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :antstructure args)) 


(defn antversion
 "Documentation for antversion to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :antversion args)) 


(defn apply
 "Executes a system command. When the os attribute is specified, the
  command is only executed when Ant is run on one of the specified
  operating systems."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :apply args)) 


(defn archives
 "Documentation for archives to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :archives args)) 


(defn assertions
 "Documentation for assertions to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :assertions args)) 


(defn attrib
 "Changes the permissions and/or attributes of a file or all files
  inside the specified directories. Currently, it has effect only under
  Windows."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :attrib args)) 


(defn attributenamespacedef
 "Documentation for attributenamespacedef to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :attributenamespacedef args)) 


(defn augment
 "Documentation for augment to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :augment args)) 


(defn available
 "Sets a property if a specified file, directory, class in the
  classpath, or JVM system resource is available at runtime."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :available args)) 


(defn basename
 "Sets a property to the last element of a specified path."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :basename args)) 


(defn bindtargets
 "Documentation for bindtargets to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :bindtargets args)) 


(defn blgenclient
 "Documentation for blgenclient to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :blgenclient args)) 


(defn buildnumber
 "Helps tracking build numbers."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :buildnumber args)) 


(defn bunzip2
 "Documentation for bunzip2 to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :bunzip2 args)) 


(defn bzip2
 "Documentation for bzip2 to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :bzip2 args)) 


(defn bzip2resource
 "Documentation for bzip2resource to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :bzip2resource args)) 


(defn cab
 "Creates Microsoft CAB archive files. It is invoked similar to the Jar
  or Zip tasks. This task will work on Windows using the external cabarc
  tool (provided by Microsoft), which must be located in your executable
  path."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :cab args)) 


(defn cccheckin
 "Documentation for cccheckin to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :cccheckin args)) 


(defn cccheckout
 "Documentation for cccheckout to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :cccheckout args)) 


(defn cclock
 "Documentation for cclock to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :cclock args)) 


(defn ccmcheckin
 "Documentation for ccmcheckin to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccmcheckin args)) 


(defn ccmcheckintask
 "Documentation for ccmcheckintask to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccmcheckintask args)) 


(defn ccmcheckout
 "Documentation for ccmcheckout to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccmcheckout args)) 


(defn ccmcreatetask
 "Documentation for ccmcreatetask to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccmcreatetask args)) 


(defn ccmkattr
 "Documentation for ccmkattr to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccmkattr args)) 


(defn ccmkbl
 "Documentation for ccmkbl to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccmkbl args)) 


(defn ccmkdir
 "Documentation for ccmkdir to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccmkdir args)) 


(defn ccmkelem
 "Documentation for ccmkelem to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccmkelem args)) 


(defn ccmklabel
 "Documentation for ccmklabel to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccmklabel args)) 


(defn ccmklbtype
 "Documentation for ccmklbtype to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccmklbtype args)) 


(defn ccmreconfigure
 "Documentation for ccmreconfigure to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccmreconfigure args)) 


(defn ccrmtype
 "Documentation for ccrmtype to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccrmtype args)) 


(defn ccuncheckout
 "Documentation for ccuncheckout to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccuncheckout args)) 


(defn ccunlock
 "Documentation for ccunlock to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccunlock args)) 


(defn ccupdate
 "Documentation for ccupdate to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ccupdate args)) 


(defn chainedmapper
 "Documentation for chainedmapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :chainedmapper args)) 


(defn checksum
 "Generates a checksum for a file or set of files. This task can also be
  used to perform checksum verifications."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :checksum args)) 


(defn chgrp
 "Changes the group ownership of a file or all files inside the
  specified directories. Currently, it has effect only under Unix."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :chgrp args)) 


(defn chmod
 "Changes the permissions of a file or all files inside the specified
  directories. Currently, it has effect only under Unix. The permissions
  are also UNIX style, like the arguments for the chmod command."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :chmod args)) 


(defn chown
 "Changes the owner of a file or all files inside the specified
  directories. Currently, it has effect only under Unix."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :chown args)) 


(defn classfileset
 "Documentation for classfileset to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :classfileset args)) 


(defn classloader
 "Documentation for classloader to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :classloader args)) 


(defn commandlauncher
 "Documentation for commandlauncher to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :commandlauncher args)) 


(defn componentdef
 "Documentation for componentdef to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :componentdef args)) 


(defn compositemapper
 "Documentation for compositemapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :compositemapper args)) 


(defn concat
 "Concatenates multiple files into a single one or to Ant's logging
  system."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :concat args)) 


(defn concatfilter
 "Documentation for concatfilter to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :concatfilter args)) 


(defn condition
 "Sets a property if a certain condition holds true; this is a
  generalization of Available and Uptodate."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :condition args)) 


(defn copy
 "Copies a file or Fileset to a new file or directory."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :copy args)) 


(defn copydir
 "[:u {} "Deprecated"]. Use the Copy task instead."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :copydir args)) 


(defn copyfile
 "[:u {} "Deprecated"]. Use the Copy task instead."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :copyfile args)) 


(defn copypath
 "Documentation for copypath to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :copypath args)) 


(defn cutdirsmapper
 "Documentation for cutdirsmapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :cutdirsmapper args)) 


(defn cvs
 "Handles packages/modules retrieved from a CVS repository."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :cvs args)) 


(defn cvschangelog
 "Documentation for cvschangelog to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :cvschangelog args)) 


(defn cvspass
 "Adds entries to a .cvspass file. Adding entries to this file has the
  same affect as a cvs login command."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :cvspass args)) 


(defn cvstagdiff
 "Generates an XML-formatted report file of the changes between two tags
  or dates recorded in a CVS repository."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :cvstagdiff args)) 


(defn cvsversion
 "Documentation for cvsversion to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :cvsversion args)) 


(defn defaultexcludes
 "Modifies the list of default exclude patterns from within your build
  file."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :defaultexcludes args)) 


(defn delete
 "Deletes either a single file, all files and sub-directories in a
  specified directory, or a set of files specified by one or more
  FileSets."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :delete args)) 


(defn deltree
 "[:u {} "Deprecated"]. Use the Delete task instead."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :deltree args)) 


(defn depend
 "Determines which class files are out-of-date with respect to their
  source, removing the class files of any other classes that depend on
  the out-of-date classes, forcing the re-compile of the removed class
  files. Typically used in conjunction with the Javac task."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :depend args)) 


(defn dependset
 "Compares a set of source files with a set of target files. If any of
  the source files is newer than any of the target files, all the target
  files are removed."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :dependset args)) 


(defn description
 "Documentation for description to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :description args)) 


(defn diagnostics
 "Documentation for diagnostics to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :diagnostics args)) 


(defn difference
 "Documentation for difference to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :difference args)) 


(defn dirname
 "Sets a property to the value of the specified file up to, but not
  including, the last path element."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :dirname args)) 


(defn dirset
 "Documentation for dirset to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :dirset args)) 


(defn ear
 "An extension of the Jar task with special treatment for files that
  should end up in an Enterprise Application archive."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ear args)) 


(defn echo
 "Echoes text to System.out or to a file."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :echo args)) 


(defn echoproperties
 "Lists the current properties."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :echoproperties args)) 


(defn echoxml
 "Documentation for echoxml to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :echoxml args)) 


(defn ejbjar
 "Documentation for ejbjar to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :ejbjar args)) 


(defn exec
 "Executes a system command. When the os attribute is specified, the
  command is only executed when Ant is run on one of the specified
  operating systems."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :exec args)) 


(defn execon
 "Documentation for execon to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :execon args)) 


(defn extension
 "Documentation for extension to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :extension args)) 


(defn extensionSet
 "Documentation for extensionSet to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :extensionSet args)) 


(defn fail
 "Exits the current build by throwing a BuildException, optionally
  printing additional information."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :fail args)) 


(defn file
 "Documentation for file to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :file args)) 


(defn filelist
 "Documentation for filelist to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :filelist args)) 


(defn files
 "Documentation for files to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :files args)) 


(defn fileset
 "Documentation for fileset to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :fileset args)) 


(defn filetokenizer
 "Documentation for filetokenizer to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :filetokenizer args)) 


(defn filter
 "Sets a token filter for this project, or reads multiple token filters
  from a specified file and sets these as filters. Token filters are
  used by all tasks that perform file-copying operations."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :filter args)) 


(defn filterchain
 "Documentation for filterchain to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :filterchain args)) 


(defn filtermapper
 "Documentation for filtermapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :filtermapper args)) 


(defn filterreader
 "Documentation for filterreader to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :filterreader args)) 


(defn filterset
 "Documentation for filterset to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :filterset args)) 


(defn first
 "Documentation for first to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :first args)) 


(defn firstmatchmapper
 "Documentation for firstmatchmapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :firstmatchmapper args)) 


(defn fixcrlf
 "Modifies a file to add or remove tabs, carriage returns, linefeeds,
  and EOF characters."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :fixcrlf args)) 


(defn flattenmapper
 "Documentation for flattenmapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :flattenmapper args)) 


(defn genkey
 "Generates a key in keystore."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :genkey args)) 


(defn get
 "Gets a file from a URL."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :get args)) 


(defn globmapper
 "Documentation for globmapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :globmapper args)) 


(defn gunzip
 "Documentation for gunzip to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :gunzip args)) 


(defn gzip
 "Documentation for gzip to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :gzip args)) 


(defn gzipresource
 "Documentation for gzipresource to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :gzipresource args)) 


(defn hostinfo
 "Sets properties related to the provided host, or to the host the
  process is run on."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :hostinfo args)) 


(defn identitymapper
 "Documentation for identitymapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :identitymapper args)) 


(defn import
 "Imports another build file and potentially overrides targets in it
  with targets of your own."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :import args)) 


(defn include
 "Includes another build file."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :include args)) 


(defn input
 "Allows user interaction during the build process by displaying a
  message and reading a line of input from the console."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :input args)) 


(defn intersect
 "Documentation for intersect to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :intersect args)) 


(defn iplanet-ejbc
 "Documentation for iplanet-ejbc to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :iplanet-ejbc args)) 


(defn isfileselected
 "Documentation for isfileselected to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :isfileselected args)) 


(defn jar
 "Jars a set of files."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :jar args)) 


(defn jarlib-available
 "Checks whether an extension is present in a FileSet or an
  ExtensionSet. If the extension is present, the specified property is
  set."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :jarlib-available args)) 


(defn jarlib-display
 "Displays the Optional Package and Package Specification information
  contained within the specified jars."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :jarlib-display args)) 


(defn jarlib-manifest
 "Generates a manifest that declares all the dependencies in manifest.
  The dependencies are determined by looking in the specified path and
  searching for Extension/Optional Package specifications in the
  manifests of the jars."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :jarlib-manifest args)) 


(defn jarlib-resolve
 "Tries to locate a jar to satisfy an extension, and places the location
  of the jar into the specified property."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :jarlib-resolve args)) 


(defn java
 "Executes a Java class within the running (Ant) JVM, or in another JVM
  if the fork attribute is specified."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :java args)) 


(defn javac
 "Compiles the specified source file(s) within the running (Ant) JVM, or
  in another JVM if the fork attribute is specified."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :javac args)) 


(defn javacc
 "Invokes the JavaCC compiler-compiler on a grammar file."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :javacc args)) 


(defn javaconstant
 "Documentation for javaconstant to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :javaconstant args)) 


(defn javadoc
 "Generates code documentation using the javadoc tool. task instead."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :javadoc args)) 


(defn javadoc2
 "Documentation for javadoc2 to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :javadoc2 args)) 


(defn javah
 "Generates JNI headers from a Java class."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :javah args)) 


(defn javaresource
 "Documentation for javaresource to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :javaresource args)) 


(defn jjdoc
 "Invokes the JJDoc documentation generator for the JavaCC
  compiler-compiler. JJDoc takes a JavaCC parser specification and
  produces documentation for the BNF grammar. It can operate in three
  modes, determined by command line options. This task only invokes
  JJDoc if the grammar file is newer than the generated BNF grammar
  documentation."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :jjdoc args)) 


(defn jjtree
 "Invokes the JJTree preprocessor for the JavaCC compiler-compiler. It
  inserts parse-tree building actions at various places in the JavaCC
  source that it generates. The output of JJTree is run through JavaCC
  to create the parser. This task only invokes JJTree if the grammar
  file is newer than the generated JavaCC file."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :jjtree args)) 


(defn jlink
 "[:u {} "Deprecated"]. Use the zipfileset and zipgroupfileset
  attributes of the Jar or Zip tasks instead."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :jlink args)) 


(defn jspc
 "Runs the JSP compiler. It can be used to precompile JSP pages for fast
  initial invocation of JSP pages, deployment on a server without the
  full JDK installed, or simply to syntax-check the pages without
  deploying them. The Javac task can be used to compile the generated
  Java source. (For WebLogic JSP compiler, see the Wljspc task.)"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :jspc args)) 


(defn last
 "Documentation for last to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :last args)) 


(defn length
 "Documentation for length to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :length args)) 


(defn libfileset
 "Documentation for libfileset to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :libfileset args)) 


(defn linetokenizer
 "Documentation for linetokenizer to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :linetokenizer args)) 


(defn loadfile
 "Loads a file into a property."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :loadfile args)) 


(defn loadproperties
 "Loads a file's contents as Ant properties. This task is equivalent to
  using <property file="..."/> except that it supports nested
  <filterchain> elements, and it cannot be specified outside a target."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :loadproperties args)) 


(defn loadresource
 "Documentation for loadresource to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :loadresource args)) 


(defn local
 "Documentation for local to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :local args)) 


(defn macrodef
 "Defines a new task as a macro built-up upon other tasks."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :macrodef args)) 


(defn mail
 "Sends SMTP email."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :mail args)) 


(defn makeurl
 "Creates a URL (list) from a file/fileset or path"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :makeurl args)) 


(defn manifest
 "Creates a manifest file."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :manifest args)) 


(defn manifestclasspath
 "Documentation for manifestclasspath to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :manifestclasspath args)) 


(defn mappedresources
 "Documentation for mappedresources to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :mappedresources args)) 


(defn mapper
 "Documentation for mapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :mapper args)) 


(defn mergemapper
 "Documentation for mergemapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :mergemapper args)) 


(defn mimemail
 "[:u {} "Deprecated"]. Use the Mail task instead."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :mimemail args)) 


(defn mkdir
 "Creates a directory. Non-existent parent directories are created, when
  necessary."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :mkdir args)) 


(defn move
 "Moves a file to a new file or directory, or a set(s) of file(s) to a
  new directory."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :move args)) 


(defn multirootfileset
 "Documentation for multirootfileset to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :multirootfileset args)) 


(defn native2ascii
 "Converts files from native encodings to ASCII with escaped Unicode. A
  common usage is to convert source files maintained in a native
  operating system encoding to ASCII, prior to compilation."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :native2ascii args)) 


(defn nice
 "Documentation for nice to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :nice args)) 


(defn packagemapper
 "Documentation for packagemapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :packagemapper args)) 


(defn parallel
 "A container task that can contain other Ant tasks. Each nested task
  specified within the <parallel> tag will be executed in its own
  thread."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :parallel args)) 


(defn patch
 "Applies a diff file to originals."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :patch args)) 


(defn path
 "Documentation for path to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :path args)) 


(defn pathconvert
 "Converts a nested path, path reference, filelist reference, or fileset
  reference to the form usable on a specified platform and/or to a list
  of items separated by the specified separator and stores the result in
  the specified property."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :pathconvert args)) 


(defn patternset
 "Documentation for patternset to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :patternset args)) 


(defn presetdef
 "Defines a new task by instrumenting an existing task with default
  values for attributes or child elements."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :presetdef args)) 


(defn projecthelper
 "Documentation for projecthelper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :projecthelper args)) 


(defn property
 "Sets a property (by name and value), or set of properties (from a file
  or resource) in the project."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :property args)) 


(defn propertyfile
 "Creates or modifies property files. Useful when wanting to make
  unattended modifications to configuration files for application
  servers and applications. Typically used for things such as
  automatically generating a build number and saving it to a build
  properties file, or doing date manipulation."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :propertyfile args)) 


(defn propertyhelper
 "Documentation for propertyhelper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :propertyhelper args)) 


(defn propertyresource
 "Documentation for propertyresource to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :propertyresource args)) 


(defn propertyset
 "Documentation for propertyset to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :propertyset args)) 


(defn pvcs
 "Documentation for pvcs to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :pvcs args)) 


(defn record
 "Documentation for record to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :record args)) 


(defn redirector
 "Documentation for redirector to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :redirector args)) 


(defn regexp
 "Documentation for regexp to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :regexp args)) 


(defn regexpmapper
 "Documentation for regexpmapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :regexpmapper args)) 


(defn rename
 "[:u {} "Deprecated"]. Use the Move task instead."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :rename args)) 


(defn renameext
 "Documentation for renameext to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :renameext args)) 


(defn replace
 "Replaces the occurrence of a given string with another string in a
  file or set of files."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :replace args)) 


(defn replaceregexp
 "Replaces the occurrence of a given regular expression with a
  substitution pattern in a file or set of files."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :replaceregexp args)) 


(defn resource
 "Documentation for resource to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :resource args)) 


(defn resourcecount
 "Documentation for resourcecount to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :resourcecount args)) 


(defn resourcelist
 "Documentation for resourcelist to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :resourcelist args)) 


(defn resources
 "Documentation for resources to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :resources args)) 


(defn restrict
 "Documentation for restrict to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :restrict args)) 


(defn retry
 "Documentation for retry to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :retry args)) 


(defn rmic
 "Runs the rmic compiler on the specified file(s)."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :rmic args)) 


(defn rpm
 "Invokes the rpm executable to build a Linux installation file. This
  task currently only works on Linux or other Unix platforms with RPM
  support."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :rpm args)) 


(defn schemavalidate
 "Documentation for schemavalidate to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :schemavalidate args)) 


(defn script
 "Executes a script in a Apache BSF-supported language."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :script args)) 


(defn scriptcondition
 "Documentation for scriptcondition to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :scriptcondition args)) 


(defn scriptdef
 "Documentation for scriptdef to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :scriptdef args)) 


(defn scriptfilter
 "Documentation for scriptfilter to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :scriptfilter args)) 


(defn scriptmapper
 "Documentation for scriptmapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :scriptmapper args)) 


(defn scriptselector
 "Documentation for scriptselector to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :scriptselector args)) 


(defn selector
 "Documentation for selector to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :selector args)) 


(defn sequential
 "A container task that can contain other Ant tasks. The nested tasks
  are simply executed in sequence. Its primary use is to support the
  sequential execution of a subset of tasks within the <parallel> tag."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :sequential args)) 


(defn serverdeploy
 "Runs a "hot" deployment tool for vendor-specific J2EE server."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :serverdeploy args)) 


(defn setpermissions
 "Changes the permissions of a collection of resources."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :setpermissions args)) 


(defn setproxy
 "Sets Java's HTTP proxy properties, so that tasks and code run in the
  same JVM can have access to remote web sites through a firewall."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :setproxy args)) 


(defn signedselector
 "Documentation for signedselector to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :signedselector args)) 


(defn signjar
 "Signs a jar or zip file with the javasign command-line tool."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :signjar args)) 


(defn sleep
 "Suspends execution for a specified period of time. Useful when a build
  or deployment process requires an interval between tasks."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :sleep args)) 


(defn sort
 "Documentation for sort to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :sort args)) 


(defn soscheckin
 "Documentation for soscheckin to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :soscheckin args)) 


(defn soscheckout
 "Documentation for soscheckout to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :soscheckout args)) 


(defn sosget
 "Documentation for sosget to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :sosget args)) 


(defn soslabel
 "Documentation for soslabel to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :soslabel args)) 


(defn sql
 "Executes a series of SQL statements via JDBC to a database. Statements
  can either be read in from a text file using the src attribute, or
  from between the enclosing SQL tags."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :sql args)) 


(defn string
 "Documentation for string to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :string args)) 


(defn stringtokenizer
 "Documentation for stringtokenizer to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :stringtokenizer args)) 


(defn style
 "Processes a set of documents via XSLT."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :style args)) 


(defn subant
 "Calls a given target for all defined sub-builds. This is an extension
  of ant for bulk project execution."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :subant args)) 


(defn substitution
 "Documentation for substitution to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :substitution args)) 


(defn symlink
 "Documentation for symlink to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :symlink args)) 


(defn sync
 "Synchronizes two directory trees."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :sync args)) 


(defn tar
 "Creates a tar archive."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :tar args)) 


(defn tarentry
 "Documentation for tarentry to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :tarentry args)) 


(defn tarfileset
 "Documentation for tarfileset to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :tarfileset args)) 


(defn taskdef
 "Adds a task definition to the current project, such that this new task
  can be used in the current project."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :taskdef args)) 


(defn tempfile
 "Generates a name for a new temporary file and sets the specified
  property to that name."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :tempfile args)) 


(defn tokens
 "Documentation for tokens to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :tokens args)) 


(defn touch
 "Changes the modification time of a file and possibly creates it at the
  same time."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :touch args)) 


(defn translate
 "Identifies keys in files, delimited by special tokens, and translates
  them with values read from resource bundles."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :translate args)) 


(defn truncate
 "Documentation for truncate to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :truncate args)) 


(defn tstamp
 "Sets the DSTAMP, TSTAMP, and TODAY properties in the current project,
  based on the current date and time."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :tstamp args)) 


(defn typedef
 "Adds a data-type definition to the current project, such that this new
  type can be used in the current project."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :typedef args)) 


(defn union
 "Documentation for union to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :union args)) 


(defn unjar
 "Documentation for unjar to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :unjar args)) 


(defn unpackagemapper
 "Documentation for unpackagemapper to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :unpackagemapper args)) 


(defn untar
 "Documentation for untar to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :untar args)) 


(defn unwar
 "Documentation for unwar to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :unwar args)) 


(defn unzip
 "Unzips a zipfile."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :unzip args)) 


(defn uptodate
 "Sets a property if a given target file is newer than a set of source
  files."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :uptodate args)) 


(defn url
 "Documentation for url to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :url args)) 


(defn verifyjar
 "Documentation for verifyjar to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :verifyjar args)) 


(defn vssadd
 "Documentation for vssadd to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :vssadd args)) 


(defn vsscheckin
 "Documentation for vsscheckin to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :vsscheckin args)) 


(defn vsscheckout
 "Documentation for vsscheckout to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :vsscheckout args)) 


(defn vsscp
 "Documentation for vsscp to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :vsscp args)) 


(defn vsscreate
 "Documentation for vsscreate to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :vsscreate args)) 


(defn vssget
 "Documentation for vssget to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :vssget args)) 


(defn vsshistory
 "Documentation for vsshistory to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :vsshistory args)) 


(defn vsslabel
 "Documentation for vsslabel to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :vsslabel args)) 


(defn waitfor
 "Blocks execution until a set of specified conditions become true. This
  task is intended to be used with the Parallel task to synchronize a
  set of processes."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :waitfor args)) 


(defn war
 "An extension of the Jar task with special treatment for files that
  should end up in the WEB-INF/lib, WEB-INF/classes, or WEB-INF
  directories of the Web Application Archive."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :war args)) 


(defn whichresource
 "Finds a class or resource."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :whichresource args)) 


(defn wljspc
 "Compiles JSP pages using WebLogic JSP compiler, weblogic.jspc. (For
  non-WebLogic JSP compiler, see the JspC task."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :wljspc args)) 


(defn xmlcatalog
 "Documentation for xmlcatalog to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :xmlcatalog args)) 


(defn xmlproperty
 "Loads property values from a well-formed XML file."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :xmlproperty args)) 


(defn xmlvalidate
 "Checks that XML files are valid (or only well-formed). This task uses
  the XML parser that is currently used by Ant by default, but any
  SAX1/2 parser can be specified, if needed."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :xmlvalidate args)) 


(defn xslt
 "Documentation for xslt to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :xslt args)) 


(defn zip
 "Creates a zipfile."
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :zip args)) 


(defn zipentry
 "Documentation for zipentry to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :zipentry args)) 


(defn zipfileset
 "Documentation for zipfileset to come!"
  {:arglists '([{:keys []} & nested])} 
  [& args] 
  (c/ant-xml :zipfileset args)) 


