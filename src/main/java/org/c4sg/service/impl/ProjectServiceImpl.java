package org.c4sg.service.impl;

import static java.util.Objects.requireNonNull;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.c4sg.dao.OrganizationDAO;
import org.c4sg.dao.ProjectDAO;
import org.c4sg.dao.ProjectSkillDAO;
import org.c4sg.dao.UserDAO;
import org.c4sg.dao.UserProjectDAO;
import org.c4sg.dto.CreateProjectDTO;
import org.c4sg.dto.JobTitleDTO;
import org.c4sg.dto.ProjectDTO;
import org.c4sg.entity.JobTitle;
import org.c4sg.entity.Organization;
import org.c4sg.entity.Project;
import org.c4sg.entity.User;
import org.c4sg.entity.UserProject;
import org.c4sg.exception.BadRequestException;
import org.c4sg.exception.ProjectServiceException;
import org.c4sg.exception.UserProjectException;
import org.c4sg.mapper.ProjectMapper;
import org.c4sg.service.AsyncEmailService;
import org.c4sg.service.C4sgUrlService;
import org.c4sg.service.ProjectService;
import org.c4sg.service.SkillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ProjectServiceImpl implements ProjectService {
	
    @Autowired
    private ProjectDAO projectDAO;

    @Autowired
    private UserDAO userDAO;
    
    @Autowired
    private UserProjectDAO userProjectDAO;
    
    @Autowired
    private ProjectSkillDAO projectSkillDAO;

    @Autowired
    private ProjectMapper projectMapper;
    
    @Autowired
    private SkillService skillService;

    @Autowired
    private AsyncEmailService asyncEmailService;
       
    @Autowired
    private OrganizationDAO organizationDAO;
    
    @Autowired
    private C4sgUrlService urlService;
    
    private static final String FROM_EMAIL = "info@code4socialgood.org";
    private static final String SUBJECT_ORGANIZATION = "You received an application from Code for Social Good";
    private static final String BODY_ORGANIZATION= "You received an application from Code for Social Good. Please login to the dashboard to review the application.";
    private static final String SUBJECT_NOTIFICATION = "Code for Social Good: New Project Notification";
    private static final String BODY_NOTIIFCATION= "You have registered to recieve notification on new projects.\n" 
    				+ "The following new project has been created:\n" + "http://dev.code4socialgood.org/project/view/";

    public void save(ProjectDTO projectDTO) {
    	Project project = projectMapper.getProjectEntityFromDto(projectDTO);
        projectDAO.save(project);
    }

    public List<ProjectDTO> findProjects() {
        List<Project> projects = projectDAO.findAllByOrderByIdDesc();
        return projectMapper.getDtosFromEntities(projects);
    }

    public ProjectDTO findById(int id) {
        return projectMapper.getProjectDtoFromEntity(projectDAO.findById(id));
    }

    public ProjectDTO findByName(String name) {
        return projectMapper.getProjectDtoFromEntity(projectDAO.findByName(name));
    }

    public Page<ProjectDTO> search(String keyWord, Integer jobTitleId, List<Integer> skills, String status, String remote,Integer page, Integer size) {
    	Page<Project> projectPages=null;
		List<Project> projects=null;
    	if (page==null){
    		page=0;
    	}		
		if (size==null){
	    	if(skills != null) {
	    		projects = projectDAO.findByKeywordAndSkill(keyWord, jobTitleId, skills, status, remote);
	    	} else {
	    		projects = projectDAO.findByKeyword(keyWord, jobTitleId, status, remote);
	    	}
			projectPages=new PageImpl<Project>(projects);	    	
		}else{
			Pageable pageable=new PageRequest(page,size);
	    	if(skills != null) {
	    		projectPages = projectDAO.findByKeywordAndSkill(keyWord, jobTitleId, skills, status, remote,pageable);
	    	} else {
	    		projectPages = projectDAO.findByKeyword(keyWord, jobTitleId, status, remote,pageable);
	    	}			
		}		
        return projectPages.map(p -> projectMapper.getProjectDtoFromEntity(p));
    }
    
    @Override
    public List<ProjectDTO> findByUser(Integer userId, String userProjectStatus) throws ProjectServiceException {
    	
    	List<Project> projects = projectDAO.findByUserIdAndUserProjectStatus(userId, userProjectStatus);  	
    	return projectMapper.getDtosFromEntities(projects);
    }

    @Override
    public List<ProjectDTO> findByOrganization(Integer orgId, String projectStatus) {
        List<Project> projects = projectDAO.getProjectsByOrganization(orgId, projectStatus);
        
        // There should always be a new project for an organization. If new project doesn't exist, create one
        if (projectStatus != null && projectStatus.equals("N")) {
        	if ((projects == null) || projects.size() == 0) {        		
            	Project project = new Project();
            	project.setOrganization(organizationDAO.findOne(orgId));
            	project.setRemoteFlag("Y");
            	project.setStatus("N");        	
            	projectDAO.save(project);
            	projects = projectDAO.getProjectsByOrganization(orgId, projectStatus);
        	}
        }
        
        return projectMapper.getDtosFromEntities(projects);
    }

    public ProjectDTO createProject(CreateProjectDTO createProjectDTO) {
        Project project = projectDAO.findByNameAndOrganizationId(
        			createProjectDTO.getName(), createProjectDTO.getOrganizationId());
        
        if (project != null) {
            System.out.println("Project already exist.");
        } else {
            project = projectDAO.save(
            		projectMapper.getProjectEntityFromCreateProjectDto(createProjectDTO));

            // Updates projectUpdateTime for the organization
            Organization localOrgan = project.getOrganization(); 
            localOrgan.setProjectUpdatedTime(new Timestamp(Calendar.getInstance().getTime().getTime())); 
            organizationDAO.save(localOrgan);

            // Notify volunteer users of new project
            List<User> notifyUsers = userDAO.findByNotify();
            for (int i=0; i<notifyUsers.size(); i++) {
            	String to = notifyUsers.get(i).getEmail();
              	String subject = SUBJECT_NOTIFICATION;
            	String body = BODY_NOTIIFCATION + project.getId();
            	asyncEmailService.send(FROM_EMAIL, to, subject, body);
            }
        }

        return projectMapper.getProjectDtoFromEntity(project);
    }

    public ProjectDTO updateProject(ProjectDTO project) {
        Project localProject = projectDAO.findById(project.getId());
        
        if (localProject != null) {
            localProject = projectDAO.save(projectMapper.getProjectEntityFromDto(project));
        } else {
            System.out.println("Project does not exist.");
        }

        return projectMapper.getProjectDtoFromEntity(localProject);
    }

    @Override
    public ProjectDTO saveUserProject(Integer userId, Integer projectId, String status ) {

        User user = userDAO.findById(userId);
        requireNonNull(user, "Invalid User Id");
        Project project = projectDAO.findById(projectId);
        requireNonNull(project, "Invalid Project Id");
        if (status == null || (!status.equals("A") && !status.equals("B") && !status.equals("C") && !status.equals("D"))) {
        	throw new BadRequestException("Invalid Project Status");
        } else {
	        isRecordExist(userId, projectId, status);
	        UserProject userProject = new UserProject();
	        userProject.setUser(user);
	        userProject.setProject(project);
	        userProject.setStatus(status);
	        userProjectDAO.save(userProject);
	    }
        
        if (status.equals("A"))
        	apply(user, project);
        		
        return projectMapper.getProjectDtoFromEntity(project);
    }

    public void deleteProject(int id) throws UserProjectException  {
        Project localProject = projectDAO.findById(id);

        if (localProject != null) {
        	// TODO delete image from S3 by frontend
        	userProjectDAO.deleteByProjectStatus(new Integer(id),"B");        	
        	projectSkillDAO.deleteByProjectId(id);            	
            projectDAO.deleteProject(id);
        } else {
            System.out.println("Project does not exist.");
        }
    }
    
	public List<JobTitleDTO> findJobTitles() {
		List<JobTitle> jobTitles = projectDAO.findJobTitles();
		return projectMapper.getJobTitleDtosFromEntities(jobTitles);
	}
    
	@Async
    private void apply(User user, Project project) {

        Integer orgId = project.getOrganization().getId();
        List<User> users = userDAO.findByOrgId(orgId);
        if (users != null && !users.isEmpty()) {
        	
        	List<String> userSkills = skillService.findSkillsForUser(user.getId());
        	User orgUser = users.get(0);
        	
        	// send organization email
			if (!StringUtils.isEmpty(user.getEmail())) {
				Map<String, Object> mailContext = new HashMap<String, Object>();
				mailContext.put("user", user);
				mailContext.put("skills", userSkills);
				mailContext.put("project", project);
				mailContext.put("message", BODY_ORGANIZATION);
				asyncEmailService.sendWithContext(FROM_EMAIL, user.getEmail(), SUBJECT_ORGANIZATION, "volunteer-application", mailContext);
			}
        	
        	// send applicant email
        	Organization org = organizationDAO.findOne(project.getOrganization().getId());
        	if(org != null) {
        		String subject = "Your C4SG Application was created";
            	Map<String, Object> appCtx = new HashMap<String, Object>();
            	appCtx.put("org", org);
            	appCtx.put("user", orgUser);
            	appCtx.put("projectLink", urlService.getProjectUrl(project.getId()));
            	appCtx.put("project", project);
            	asyncEmailService.sendWithContext(FROM_EMAIL, user.getEmail(), subject, "applicant-application", appCtx);
        	}
        	
        	System.out.println("Application email sent: Project=" + project.getId() + " ; Applicant=" + user.getId() + " ; OrgEmail=" + orgUser.getEmail());
        }
    }
        
    private void isRecordExist(Integer userId, Integer projectId, String status) throws UserProjectException {

    	List<UserProject> userProjects = userProjectDAO.findByUser_IdAndProject_IdAndStatus(userId, projectId, status);
    	
    	requireNonNull(userProjects, "Invalid operation");
    	for(UserProject userProject : userProjects)
    	{
    		if(userProject.getStatus().equals(status))
        	{
        		throw new UserProjectException("Record already exist");
        	}
    	}    	
    }
    
	@Override
	public void saveImage(Integer id, String imgUrl) {
				
		projectDAO.updateImage(imgUrl, id);
	}
}
